/*
 * #%L
 * Fuse Patch :: Core
 * %%
 * Copyright (C) 2015 Private
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.wildfly.extras.patch.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.wildfly.extras.patch.Patch;
import org.wildfly.extras.patch.PatchId;
import org.wildfly.extras.patch.PatchMetadata;
import org.wildfly.extras.patch.PatchMetadataBuilder;
import org.wildfly.extras.patch.PatchTool;
import org.wildfly.extras.patch.Record;
import org.wildfly.extras.patch.utils.IllegalArgumentAssertion;
import org.wildfly.extras.patch.utils.IllegalStateAssertion;

public final class MetadataParser {

    public static final String MANAGED_PATHS = "managed-paths.metadata";
    
    static final String VERSION_PREFIX = "# fusepatch:";
    static final String PATCHID_PREFIX = "# patch id:";

    public static Patch buildPatchFromZip(PatchId patchId, Record.Action action, ZipInputStream zipInput) throws IOException {
        IllegalArgumentAssertion.assertNotNull(zipInput, "zipInput");

        Set<Record> records = new HashSet<>();
        byte[] buffer = new byte[64 * 1024];
        ZipEntry entry = zipInput.getNextEntry();
        while (entry != null) {
            if (!entry.isDirectory()) {
                String name = entry.getName();
                int read = zipInput.read(buffer);
                while (read > 0) {
                    read = zipInput.read(buffer);
                }
                long crc = entry.getCrc();
                records.add(Record.create(patchId, action, Paths.get(name), crc));
            }
            entry = zipInput.getNextEntry();
        }
        PatchMetadataBuilder mdbuilder = new PatchMetadataBuilder().patchId(patchId);
        return Patch.create(mdbuilder.build(), records);
    }

    public static Patch readPatch(Path rootPath, PatchId patchId) throws IOException {
        IllegalArgumentAssertion.assertNotNull(rootPath, "rootPath");
        IllegalArgumentAssertion.assertNotNull(patchId, "patchId");
        File metadata = getMetadataFile(rootPath, patchId);
        return metadata.isFile() ? readPatch(metadata) : null;
    }

    public static List<PatchId> queryAvailablePatches(Path rootPath, final String prefix, boolean latest) {
        IllegalArgumentAssertion.assertNotNull(rootPath, "rootPath");
        final Map<String, TreeSet<PatchId>> auxmap = new HashMap<>();
        if (rootPath.toFile().exists()) {
            try {
                Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                        String name = path.getFileName().toString();
                        if (!MANAGED_PATHS.equals(name) && name.endsWith(".metadata")) {
                            if (prefix == null || name.startsWith(prefix)) {
                                PatchId patchId = PatchId.fromURL(path.toUri().toURL());
                                TreeSet<PatchId> idset = auxmap.get(patchId.getName());
                                if (idset == null) {
                                    idset = new TreeSet<>();
                                    auxmap.put(patchId.getName(), idset);
                                }
                                idset.add(patchId);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        Set<PatchId> sortedSet = new TreeSet<>();
        for (TreeSet<PatchId> set : auxmap.values()) {
            if (latest) {
                sortedSet.add(set.last());
            } else {
                sortedSet.addAll(set);
            }
        }
        List<PatchId> result = new ArrayList<>(sortedSet);
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    public static void writePatch(Path rootPath, Patch patch) throws IOException {
        IllegalArgumentAssertion.assertNotNull(rootPath, "rootPath");
        IllegalArgumentAssertion.assertNotNull(patch, "patch");
        File metadataFile = getMetadataFile(rootPath, patch.getPatchId());
        metadataFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(metadataFile)) {
            writePatch(patch, fos, true);
        }
    }

    public static File getMetadataDirectory(Path rootPath, PatchId patchId) {
        return rootPath.resolve(Paths.get(patchId.getName(), patchId.getVersion().toString())).toFile();
    }

    public static File getMetadataFile(Path rootPath, PatchId patchId) {
        return getMetadataDirectory(rootPath, patchId).toPath().resolve(patchId + ".metadata").toFile();
    }

    public static void writePatch(Patch patch, OutputStream outstream, boolean addHeader) throws IOException {
        IllegalArgumentAssertion.assertNotNull(patch, "patch");
        IllegalArgumentAssertion.assertNotNull(outstream, "outstream");
        try (PrintStream pw = new PrintStream(outstream)) {

            if (addHeader) {
                pw.println(VERSION_PREFIX + " " + PatchTool.VERSION);
                pw.println(PATCHID_PREFIX + " " + patch.getPatchId());
            }

            pw.println();
            pw.println("[properties]");
            PatchMetadata metadata = patch.getMetadata();
            if (!metadata.getRoles().isEmpty()) {
                String spec = metadata.getRoles().toString();
                spec = spec.substring(1, spec.length() - 1);
                pw.println("Roles: " + spec);
            }
            if (!metadata.getDependencies().isEmpty()) {
                String spec = metadata.getDependencies().toString();
                spec = spec.substring(1, spec.length() - 1);
                pw.println("Dependencies: " + spec);
            }

            pw.println();
            pw.println("[content]");
            for (Record rec : patch.getRecords()) {
                pw.println(rec.toString());
            }

            List<String> commands = metadata.getPostCommands();
            if (!commands.isEmpty()) {
                pw.println();
                pw.println("[post-install-commands]");
                for (String cmd : commands) {
                    pw.println(cmd);
                }
            }
        }
    }

    public static Patch readPatch(File metadataFile) throws IOException {
        IllegalArgumentAssertion.assertNotNull(metadataFile, "metadataFile");
        IllegalArgumentAssertion.assertTrue(metadataFile.isFile(), "Cannot find metadata file: " + metadataFile);

        PatchMetadataBuilder mdbuilder = new PatchMetadataBuilder();
        Set<Record> records = new HashSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(metadataFile))) {
            String line = br.readLine().trim();
            IllegalStateAssertion.assertTrue(line.startsWith(VERSION_PREFIX), "Cannot obtain version info");
            line = br.readLine().trim();
            IllegalStateAssertion.assertTrue(line.startsWith(PATCHID_PREFIX), "Cannot obtain patch id");
            mdbuilder.patchId(PatchId.fromString(line.substring(PATCHID_PREFIX.length()).trim()));
            
            String mode = null;
            while (line != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    line = br.readLine();
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    mode = line;
                    line = br.readLine();
                    continue;
                }
                if ("[properties]".equals(mode)) {
                    String[] toks = line.split(":");
                    IllegalStateAssertion.assertEquals(2, toks.length, "Illegal property spec: " + line);
                    String name = toks[0].trim();
                    String value = toks[1].trim();
                    if ("Roles".equals(name)) {
                        for (String tok : value.split(",")) {
                            mdbuilder.roles(tok.trim());
                        }
                    }
                    if ("Dependencies".equals(name)) {
                        for (String tok : value.split(",")) {
                            mdbuilder.dependencies(PatchId.fromString(tok.trim()));
                        }
                    }
                }
                if ("[content]".equals(mode)) {
                    records.add(Record.fromString(line));
                }
                if ("[post-install-commands]".equals(mode)) {
                    mdbuilder.postCommands(line);
                }
                line = br.readLine();
            }
            return Patch.create(mdbuilder.build(), records);
        }
    }
}
