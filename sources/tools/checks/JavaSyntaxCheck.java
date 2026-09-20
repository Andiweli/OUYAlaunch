import com.sun.source.util.JavacTask;
import java.util.Arrays;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parse Java 8 syntax only. NOT a replacement for Android compilation/type checking. */
public final class JavaSyntaxCheck {
    public static void main(String[] paths) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    Arrays.asList("-proc:none", "-source", "8", "-Xlint:-options"), null,
                    files.getJavaFileObjects(paths));
            task.parse();
            boolean error = false;
            for (Diagnostic<? extends JavaFileObject> message : diagnostics.getDiagnostics()) {
                if (message.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println(message); error = true;
                }
            }
            if (error) throw new AssertionError("Java syntax errors");
        }
        System.out.println("JavaSyntaxCheck: " + paths.length + " Java source files parsed (syntax only)");
    }
}
