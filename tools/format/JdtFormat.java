import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

/**
 * Headless Eclipse JDT formatter driver.
 *   java -cp <jdt jars> JdtFormat --config <props> [--check | --apply | --stdout] <file|dir>...
 * Exit 0 = clean/applied, 1 = --check found differences, 2 = error.
 */
public class JdtFormat {
    public static void main(String[] args) throws Exception {
        String config = null;
        boolean check = false, apply = false, stdout = false;
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = args[++i];
                case "--check" -> check = true;
                case "--apply" -> apply = true;
                case "--stdout" -> stdout = true;
                default -> roots.add(Paths.get(args[i]));
            }
        }
        if (config == null || roots.isEmpty() || (check == apply && !stdout)) {
            System.err.println("usage: JdtFormat --config <props> (--check | --apply | --stdout) <file|dir>...");
            System.exit(2);
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(config))) {
            props.load(in);
        }
        Map<String, String> options = props.stringPropertyNames().stream()
                .collect(Collectors.toMap(k -> k, props::getProperty));
        CodeFormatter formatter = ToolFactory.createCodeFormatter(options);

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> w = Files.walk(root)) {
                    w.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                }
            } else files.add(root);
        }
        files.sort(null);

        int changed = 0;
        for (Path f : files) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            TextEdit edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT
                    | CodeFormatter.F_INCLUDE_COMMENTS, src, 0, src.length(), 0, "\n");
            if (edit == null) {
                System.err.println("no edit: " + f);
                continue;
            }
            Document doc = new Document(src);
            edit.apply(doc);
            String out = doc.get();
            if (stdout) System.out.print(out);
            else if (!out.equals(src)) {
                changed++;
                if (check) System.out.println("would reformat: " + f);
                if (apply) Files.writeString(f, out, StandardCharsets.UTF_8);
            }
        }
        if (check && changed > 0) {
            System.out.println(changed + " of " + files.size() + " files would be reformatted");
            System.exit(1);
        }
    }
}
