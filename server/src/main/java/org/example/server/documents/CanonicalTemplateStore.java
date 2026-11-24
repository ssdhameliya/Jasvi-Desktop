package org.example.server.documents;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.documentstudio.model.DocumentType;
import org.example.documentstudio.model.ExcelTemplate;
import org.example.documentstudio.model.TemplateStatus;
import org.example.server.authority.ServerResourceService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Resolves the company-server mirrors of the same Document Studio Excel defaults used by desktop. */
@Service
public class CanonicalTemplateStore {
    private static final String EXCEL_TYPE = "EXCEL_TEMPLATE";
    private final ServerResourceService resources;
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public CanonicalTemplateStore(ServerResourceService resources) {
        this.resources = resources;
    }

    public Optional<ExcelSelection> excel(DocumentType type) throws IOException {
        List<ExcelSelection> matches = new ArrayList<>();
        for (var meta : resources.list(EXCEL_TYPE)) {
            Path root = null;
            try {
                root = extract(resources.get(EXCEL_TYPE, meta.key()).content(), "excel-template-");
                Path templateMeta = root.resolve("template.json");
                if (!Files.isRegularFile(templateMeta)) { deleteTree(root); continue; }
                ExcelTemplate template = json.readValue(templateMeta.toFile(), ExcelTemplate.class);
                if (template.getDocumentType() != type || template.getStatus() != TemplateStatus.ACTIVE || !template.isDefaultTemplate()) {
                    deleteTree(root); continue;
                }
                if (!Files.isRegularFile(root.resolve(template.getSourceFile()))) { deleteTree(root); continue; }
                matches.add(new ExcelSelection(root, template, safe(template.getUpdatedAt())));
            } catch (Exception error) {
                if (root != null) deleteTree(root);
            }
        }
        if (matches.isEmpty()) return Optional.empty();
        matches.sort(Comparator.comparing(ExcelSelection::updatedAt).reversed());
        ExcelSelection selected = matches.getFirst();
        for (int i=1;i<matches.size();i++) matches.get(i).close();
        return Optional.of(selected);
    }

    private static Path extract(byte[] archive, String prefix) throws IOException {
        if (archive == null || archive.length == 0) throw new IOException("Template archive is empty.");
        Path root = Files.createTempDirectory(prefix).toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("Unsafe template archive entry.");
                if (entry.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        } catch (Exception error) {
            deleteTree(root);
            if (error instanceof IOException io) throw io;
            throw new IOException("Template archive could not be extracted.", error);
        }
        return root;
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
    }
    private static String safe(String value) { return value == null ? "" : value; }

    public record ExcelSelection(Path root, ExcelTemplate template, String updatedAt) implements AutoCloseable {
        @Override public void close() { CanonicalTemplateStore.deleteTree(root); }
    }
}
