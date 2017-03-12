package org.walkmod.gradle.providers;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.ProjectConnection;
import org.walkmod.conf.ConfigurationException;

public class GradleUtils {

    public int getAndroidVersion(String line) {
        Pattern p = Pattern.compile("[0-9]+");
        Matcher m = p.matcher(line);
        Integer value = null;
        while (m.find()) {
            value = Integer.parseInt(m.group());
        }
        if (value == null) {
            throw new ConfigurationException(
                    "Invalid compileSdkVersion (" + line + "). Please provide an integer value");
        }
        return value;
    }

    public Integer getCompileAndroidSDKVersion(File projectDir) {
        File cfg = new File(projectDir, "build.gradle");
        Integer version = null;
        Integer compileSDKVersion = null;
        try {
            List<String> cfgContent = FileUtils.readLines(cfg);
            Iterator<String> it = cfgContent.iterator();
            while (it.hasNext() && compileSDKVersion == null) {
                String line = it.next();
                int index = line.indexOf("minSdkVersion ");
                if (index != -1) {

                    version = getAndroidVersion(line.substring(index + "minSdkVersion ".length()).trim());
                } else {
                    index = line.indexOf("compileSdkVersion ");
                    if (index != -1) {

                        compileSDKVersion = getAndroidVersion(
                                line.substring(index + "compileSdkVersion ".length()).trim());

                    }
                }
            }
            if (compileSDKVersion != null) {
                version = compileSDKVersion;
            }

        } catch (IOException e) {
            throw new ConfigurationException("Error reading the build.gradle", e.getCause());
        }
        return version;
    }
    
    
    public List<String> getDepsCoordinates(ProjectConnection connection, File buildFile) {
        BuildLauncher launcher = connection.newBuild();
        if (buildFile != null) {
            launcher.withArguments("-b", buildFile.getAbsolutePath());
        }
        launcher.forTasks("dependencies");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        launcher.setStandardOutput(bos);
        launcher.setStandardError(System.err);
        launcher.run();
        String content = bos.toString();
        String[] lines = content.split("\\n");
        List<String> coordinates = new LinkedList<String>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("compile") || lines[i].startsWith("provided")) {
                if (i + 1 < lines.length && !lines[i + 1].startsWith("No dependencies")) {
                    int j = i + 1;
                    String prefix = null;
                    String nextGoal = "debugApk";
                    if (lines[i].startsWith("provided")) {
                        nextGoal = "releaseApk";
                    }
                    while (j < lines.length && !lines[j].startsWith(nextGoal)) {
                        int index = lines[j].lastIndexOf("--");
                        if (index != -1) {
                            prefix = lines[j].substring(0, index + 2);

                            if (lines[j].length() > prefix.length()) {

                                String artifact = lines[j].substring(prefix.length()).trim();
                                if (!artifact.endsWith("(*)")) {
                                    int dynamicVersionIndex = artifact.indexOf("->");
                                    if (dynamicVersionIndex != -1) {
                                        String artifactName = artifact.substring(0, artifact.lastIndexOf(":")).trim();
                                        String version = artifact.substring(dynamicVersionIndex + 2).trim();
                                        artifact = artifactName + ":" + version;
                                    }
                                    coordinates.add(artifact);
                                }

                            }
                        }
                        j++;
                    }

                }
            }
        }
        return coordinates;
    }
    
    
    public void unzipAAR(String zipFilePath, String destDirectory) throws IOException {
        File destDir = new File(destDirectory);
        if (!destDir.exists()) {
            destDir.mkdir();
        }
        ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry entry = zipIn.getNextEntry();
        // iterates over entries in the zip file
        while (entry != null) {
            String filePath = destDirectory + File.separator + entry.getName();
            if (!entry.isDirectory()) {
                if (entry.getName().endsWith(".jar")) {
                    // if the entry is a file, extracts it
                    extractFile(zipIn, filePath);
                }
            } else {
                if (entry.getName().endsWith("jars/") || entry.getName().endsWith("libs/")) {
                    File dir = new File(filePath);
                    dir.mkdir();
                }
            }
            zipIn.closeEntry();
            entry = zipIn.getNextEntry();
        }
        zipIn.close();
    }
    
    
    private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
        byte[] bytesIn = new byte[4096];
        int read = 0;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }
    
    public Collection<File> resolveArtifacts(String userHomeDir, List<String> coordinates) throws ConfigurationException {
        Collection<File> result = new LinkedList<File>();
      
        GradleUtils gradleUtils = new GradleUtils();
        if (coordinates != null) {
            File aux = new File(userHomeDir, "caches" + File.separator + "modules-2" + File.separator + "files-2.1");
            for (String coordinate : coordinates) {
                String[] parts = coordinate.split(":");
                if (parts.length == 3) {
                    File groupIdDir = new File(aux, parts[0]);
                    File artifactIdDir = new File(groupIdDir, parts[1]);
                    File versionDir = new File(artifactIdDir, parts[2]);
                    File[] subdirs = versionDir.listFiles();
                    boolean found = false;
                    if (subdirs == null) {
                        //Trying inside the android m2 extras
                        String androidHome = System.getenv("ANDROID_HOME");
                        if (androidHome != null && !"".equals(androidHome)) {
                            File androidHomeFile = new File(androidHome);
                            File repo = null;
                            if (parts[0].contains("google")) {
                                repo = new File(androidHomeFile,
                                        "extras" + File.separator + "google" + File.separator + "m2repository");
                            } else {
                                repo = new File(androidHomeFile,
                                        "extras" + File.separator + "android" + File.separator + "m2repository");
                            }
                            groupIdDir = new File(repo, resolvePath(parts[0]));
                            artifactIdDir = new File(groupIdDir, parts[1]);
                            versionDir = new File(artifactIdDir, parts[2]);
                            File aarFile = new File(versionDir, parts[1] + "-" + parts[2] + ".aar");
                            if (aarFile.exists()) {
                                try {
                                    File jarClasses = new File(versionDir, "classes.jar");
                                    //if (!jarClasses.exists()) {
                                    gradleUtils.unzipAAR(aarFile.getAbsolutePath(), versionDir.getAbsolutePath());
                                    //}
                                    result.add(jarClasses);
                                    File libFolder = new File(versionDir, "libs");
                                    if (libFolder.exists()) {

                                        subdirs = libFolder.listFiles();
                                        for (File lib : subdirs) {
                                            result.add(lib);
                                        }
                                    }
                                } catch (IOException e) {
                                    throw new ConfigurationException(
                                            "Error extracting the aar file " + aarFile.getAbsolutePath(), e);
                                }
                            } else {
                                File jarFile = new File(versionDir, parts[1] + "-" + parts[2] + ".jar");
                                if (jarFile.exists()) {
                                    result.add(jarFile);
                                } else {
                                    System.out.println(coordinate);
                                }
                            }
                        }
                    } else {
                        for (int i = 0; i < subdirs.length && !found; i++) {
                            File parentDir = subdirs[i];
                            if (parentDir.isDirectory()) {
                                File file = new File(parentDir, parts[1] + "-" + parts[2] + ".jar");
                                if (file.exists()) {
                                    result.add(file);
                                    found = true;
                                } else {
                                    file = new File(parentDir, parts[1] + "-" + parts[2] + ".aar");
                                    if (file.exists()) {
                                        try {
                                            File jarClasses = new File(parentDir, "classes.jar");
                                            if (!jarClasses.exists()) {
                                                gradleUtils.unzipAAR(file.getAbsolutePath(), parentDir.getAbsolutePath());
                                            }
                                            result.add(jarClasses);
                                            File libFolder = new File(parentDir, "libs");
                                            if (libFolder.exists()) {
                                                File[] deps = libFolder.listFiles();
                                                for (File lib : deps) {
                                                    result.add(lib);
                                                }
                                            }
                                            found = true;
                                        } catch (IOException e) {
                                            throw new ConfigurationException("Error extracting aar", e);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
    

    public String resolvePath(String groupId){
       return groupId.replaceAll("\\.", getFileSeparator());
    }
    
    
    public String getFileSeparator(){
        if(File.separatorChar == '\\'){
            return "\\\\";
        }
        return File.separator;
    }
    
    
    public File getAndroidJar(Integer version) {
        String androidHome = System.getenv("ANDROID_HOME");
        if (androidHome != null && !"".equals(androidHome)) {

            File jarDir = new File(androidHome, "platforms/");

            if (jarDir.exists()) {
                Integer versionNumber = null;
                File[] files = jarDir.listFiles();
                for (int i = 0; i < files.length; i++) {
                    String fileName = files[i].getName();
                    if (fileName.startsWith("android-")) {
                        try {
                            Integer aux = Integer.parseInt(fileName.substring("android-".length()));
                            if (aux >= version) {
                                if (versionNumber == null || versionNumber > aux) {
                                    versionNumber = aux;
                                }
                            }
                        } catch (NumberFormatException e) {

                        }
                    }
                }
                if (versionNumber != null) {
                    return (new File(new File(jarDir, "android-" + versionNumber), "android.jar"));
                }
            }
        }
        return null;
    }

}
