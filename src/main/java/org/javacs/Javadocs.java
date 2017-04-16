package org.javacs;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.sun.javadoc.*;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javadoc.api.JavadocTool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DocumentationTool;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Javadocs {

    /**
     * Stores known javadocs across all source paths, including dependencies
     */
    private static Javadocs global = new Javadocs(Collections.emptySet());

    /**
     * Add another source path to global()
     */
    public static void addSourcePath(Set<Path> additionalSourcePath) {
        global = global.withSourcePath(additionalSourcePath);
    }

    /**
     * A single global instance of Javadocs that incorporates all source paths
     */
    public static Javadocs global() {
        return global;
    }

    /**
     * The indexed source path, not including src.zip
     */
    private final Set<Path> userSourcePath;

    /**
     * Cache for performance reasons
     */
    private final JavacFileManager actualFileManager;

    /**
     * Empty file manager we pass to javadoc to prevent it from roaming all over the place
     */
    private final JavacFileManager emptyFileManager = JavacTool.create().getStandardFileManager(Javadocs::onDiagnostic, null, null);

    /**
     * All the classes we have indexed so far
     */
    private final Map<String, RootDoc> topLevelClasses = new ConcurrentHashMap<>();

    private final Types types;

    private final Elements elements;

    Javadocs(Set<Path> sourcePath) {
        this.userSourcePath = sourcePath;
        this.actualFileManager = createFileManager(allSourcePaths(sourcePath));

        JavacTask task = JavacTool.create().getTask(
                null,
                emptyFileManager, 
                Javadocs::onDiagnostic,
                null,
                null,
                null
        );

        types = task.getTypes();
        elements = task.getElements();
    }

    private static Set<File> allSourcePaths(Set<Path> userSourcePath) {
        Set<File> allSourcePaths = new HashSet<>();

        // Add userSourcePath
        for (Path each : userSourcePath) 
            allSourcePaths.add(each.toFile());

        // Add src.zip from JDK
        findSrcZip().ifPresent(allSourcePaths::add);

        return allSourcePaths;
    }

    private static JavacFileManager createFileManager(Set<File> allSourcePaths) {
        JavacFileManager actualFileManager = JavacTool.create().getStandardFileManager(Javadocs::onDiagnostic, null, null);

        try {
            actualFileManager.setLocation(StandardLocation.SOURCE_PATH, allSourcePaths);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return actualFileManager;
    }

    private Javadocs withSourcePath(Set<Path> additionalSourcePath) {
        Set<Path> all = new HashSet<>();

        all.addAll(userSourcePath);
        all.addAll(additionalSourcePath);

        return new Javadocs(all);
    }

    /**
     * Get docstring for method, using inherited method if necessary
     */
    static Optional<String> commentText(MethodDoc doc) {
        // TODO search interfaces as well
        
        while (doc != null && Strings.isNullOrEmpty(doc.commentText()))
            doc = doc.overriddenMethod();
        
        if (doc == null || Strings.isNullOrEmpty(doc.commentText()))
            return Optional.empty();
        else 
            return Optional.of(doc.commentText());
    }

    Optional<? extends ProgramElementDoc> doc(Element el) {
        if (el instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) el;

            return methodDoc(method);
        }
        else if (el instanceof TypeElement) {
            TypeElement type = (TypeElement) el;

            return classDoc(type);
        }
        else return Optional.empty();
    }

    Optional<MethodDoc> methodDoc(ExecutableElement method) {
        TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();

        return classDoc(enclosingClass)
                .flatMap(classDoc -> doMethodDoc(classDoc, method));
    }

    private Optional<MethodDoc> doMethodDoc(ClassDoc classDoc, ExecutableElement method) {
        for (MethodDoc each : classDoc.methods(false)) {
            if (methodMatches(method, each))
                return Optional.of(each);
        }

        return Optional.empty();
    }

    private boolean methodMatches(ExecutableElement method, MethodDoc doc) {
        return method.getSimpleName().contentEquals(doc.name()) &&
            paramsMatch(method.getParameters(), doc.parameters());
    }

    private boolean paramsMatch(List<? extends VariableElement> params, Parameter[] docs) {
        if (params.size() != docs.length) 
            return false;
        
        for (int i = 0; i < docs.length; i++) {
            String paramType = paramType(params.get(i));
            String docType = docType(docs[i]);

            if (!paramType.equals(docType))
                return false;
        }

        return true;
    }

    private String paramType(VariableElement param) {
        return types.erasure(param.asType()).toString();
    }

    private String docType(Parameter doc) {
        return doc.type().qualifiedTypeName() + doc.type().dimension();
    }

    Optional<ConstructorDoc> constructorDoc(ExecutableElement method) {
        TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();

        return classDoc(enclosingClass)
                .flatMap(classDoc -> doConstructorDoc(classDoc, method));
    }

    private Optional<ConstructorDoc> doConstructorDoc(ClassDoc classDoc, ExecutableElement method) {
        for (ConstructorDoc each : classDoc.constructors(false)) {
            if (constructorMatches(method, each))
                return Optional.of(each);
        }

        return Optional.empty();
    }

    private boolean constructorMatches(ExecutableElement method, ConstructorDoc doc) {
        return paramsMatch(method.getParameters(), doc.parameters());
    }

    Optional<ClassDoc> classDoc(TypeElement type) {
        String className = type.getQualifiedName().toString();
        RootDoc index = index(className);

        return Optional.ofNullable(index.classNamed(className));
    }

    void update(JavaFileObject source) {
        LOG.info("Update javadocs for " + source.toUri());

        DocumentationTool.DocumentationTask task = new JavadocTool().getTask(
            null,
            emptyFileManager,
            Javadocs::onDiagnostic,
            Javadocs.class,
            ImmutableList.of("-private"),
            ImmutableList.of(source)
        );

        task.call();

        getSneakyReturn().ifPresent(root -> updateCache(root, source));
    }

    private void updateCache(RootDoc root, JavaFileObject source) {
        for (ClassDoc each : root.classes()) {
            if (source.isNameCompatible(each.simpleTypeName(), JavaFileObject.Kind.SOURCE)) {
                topLevelClasses.put(each.qualifiedName(), root);

                return;
            }
        }
    }

    private final ForkJoinPool indexPool = new ForkJoinPool(1);

    /**
     * Get or compute the javadoc for `className`
     */
    RootDoc index(String className) {
        if (topLevelClasses.containsKey(className))
            return topLevelClasses.get(className);
        else {
            // Asynchronously fetch docs
            if (indexPool.isQuiescent())
                indexPool.submit(() -> force(className));
            else
                LOG.warning("Javadoc is already running, rejecting " + className + " for now");

            return EmptyRootDoc.INSTANCE;
        }
    }

    void force(String className) {
        topLevelClasses.put(className, doIndex(className));
    }

    /**
     * Read all the Javadoc for `className`
     */
    private RootDoc doIndex(String className) {
        try {
            JavaFileObject source = actualFileManager.getJavaFileForInput(StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);

            if (source == null) {
                LOG.warning("No source file for " + className);

                return EmptyRootDoc.INSTANCE;
            }
            
            LOG.info("Found " + source.toUri() + " for " + className);

            DocumentationTool.DocumentationTask task = new JavadocTool().getTask(
                    null,
                    emptyFileManager,
                    Javadocs::onDiagnostic,
                    Javadocs.class,
                    null,
                    ImmutableList.of(source)
            );

            task.call();
        } catch (IOException e) {
            throw new RuntimeException();
        }

        return getSneakyReturn().orElse(EmptyRootDoc.INSTANCE);
    }

    private Optional<RootDoc> getSneakyReturn() {
        RootDoc result = sneakyReturn.get();
        sneakyReturn.remove();

        if (result == null) {
            LOG.warning("index did not return a RootDoc");

            return Optional.empty();
        }
        else return Optional.of(result);
    }

    /**
     * start(RootDoc) uses this to return its result to doIndex(...)
     */
    private static ThreadLocal<RootDoc> sneakyReturn = new ThreadLocal<>();

    /**
     * Called by the javadoc tool
     *
     * {@link Doclet}
     */
    public static boolean start(RootDoc root) {
        sneakyReturn.set(root);

        return true;
    }

    /**
     * Find the copy of src.zip that comes with the system-installed JDK
     */
    private static Optional<File> findSrcZip() {
        Path path = Paths.get(System.getProperty("java.home"));

        if (path.endsWith("jre"))
            path = path.getParent();

        path = path.resolve("src.zip");

        File file = path.toFile();

        if (file.exists())
            return Optional.of(file);
        else
            return Optional.empty();
    }

    private static void onDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        Level level = level(diagnostic.getKind());
        String message = diagnostic.getMessage(null);

        LOG.log(level, message);
    }

    private static Level level(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return Level.SEVERE;
            case WARNING:
            case MANDATORY_WARNING:
                return Level.WARNING;
            case NOTE:
                return Level.INFO;
            case OTHER:
            default:
                return Level.FINE;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");

}