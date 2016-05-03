/*
 Copyright (C) 2015 Raquel Pau and Albert Coroleu.
 
Walkmod is free software: you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Walkmod is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License
along with Walkmod.  If not, see <http://www.gnu.org/licenses/>.*/
package org.walkmod.gradle.providers;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseProjectDependency;
import org.walkmod.conf.ConfigurationException;
import org.walkmod.conf.ConfigurationProvider;
import org.walkmod.conf.entities.Configuration;

import com.alibaba.fastjson.JSONArray;

public class ClassLoaderConfigurationProvider implements ConfigurationProvider {

   private File pomFile = null;

   private String buildDir = "classes";

   private Configuration configuration;

   private String installationDir;

   private String userHomeDir;

   private String workingDirectory;

   private String task = "assemble";

   private String flavor = null;

   private GradleConnector connector = null;

   private String gradleVersion = null;

   private JSONArray localLibs = null;

   public ClassLoaderConfigurationProvider() {
   }

   public void setInstallationDir(String installationDir) {
      this.installationDir = installationDir;
   }

   public void setUserHomeDir(String userHomeDir) {
      this.userHomeDir = userHomeDir;
   }

   public void setWorkingDirectory(String workingDirectory) {
      this.workingDirectory = workingDirectory;
   }

   public void setLocalLibs(JSONArray localLibs) {
      this.localLibs = localLibs;
   }

   public void setBuildDir(String buildDir) {
      this.buildDir = buildDir;
   }

   public String getBuildDir() {
      return buildDir;
   }

   public void setFlavor(String flavor) {
      this.flavor = flavor;
   }

   public File getPomFile() {
      return pomFile;
   }

   public void setPomFile(File file) {
      this.pomFile = file;
   }

   public void setGradleVersion(String gradleVersion) {
      this.gradleVersion = gradleVersion;
   }

   @Override
   public void init(Configuration configuration) {
      this.configuration = configuration;
   }

   public GradleConnector getConnector() throws ConfigurationException {
      if (connector == null) {
         connector = GradleConnector.newConnector();
         if (gradleVersion != null) {
            connector.useGradleVersion(gradleVersion);
         }
         if (installationDir != null) {
            connector.useInstallation(new File(installationDir));
            if (userHomeDir != null) {
               connector.useGradleUserHomeDir(new File(userHomeDir));
            } else {
               try {
                  userHomeDir = new File(System.getProperty("user.home"), ".gradle").getCanonicalPath();
               } catch (IOException e) {
                  throw new ConfigurationException("Error resolving the working directory", e.getCause());
               }
            }
         } else {
            try {
               userHomeDir = new File(System.getProperty("user.home"), ".gradle").getCanonicalPath();
            } catch (IOException e) {
               throw new ConfigurationException("Error resolving the working directory", e.getCause());
            }
         }
         if (workingDirectory == null) {
            try {
               workingDirectory = new File(".").getCanonicalPath();
            } catch (IOException e) {
               throw new ConfigurationException("Error resolving the working directory", e.getCause());
            }
         }

         connector.forProjectDirectory(new File(workingDirectory));
      }
      return connector;
   }

   public void compile() throws Exception {

      ProjectConnection connection = getConnector().connect();
      try {
         // Configure the build
         BuildLauncher launcher = connection.newBuild();
         if (flavor != null) {
            launcher.forTasks(task + StringUtils.capitalize(flavor));
         } else {
            launcher.forTasks(task);
         }
         launcher.setStandardOutput(System.out);
         launcher.setStandardError(System.err);

         // Run the build
         launcher.run();
      } finally {
         // Clean up
         connection.close();
      }
   }

   private List<String> getDepsCoordinates(ProjectConnection connection) {
      BuildLauncher launcher = connection.newBuild();
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

   private Integer getCompileAndroidSDKVersion(File projectDir) {
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

               version = Integer.parseInt(line.substring(index + "minSdkVersion ".length()).trim());
            } else {
               index = line.indexOf("compileSdkVersion ");
               if (index != -1) {
                  compileSDKVersion = Integer.parseInt(line.substring(index + "compileSdkVersion ".length()).trim());
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

   private File getAndroidJar(Integer version) {
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
                     if (aux > version) {
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

   public List<File> getClassPathFiles() throws ConfigurationException {
      ProjectConnection connection = getConnector().connect();
      LinkedHashSet<File> classPathFiles = new LinkedHashSet<File>();
      try {
         // Load the Eclipse model for the project
         EclipseProject project = connection.getModel(EclipseProject.class);
         File gradleBuildDir = project.getGradleProject().getBuildDirectory();
         File classesDir = new File(gradleBuildDir, buildDir);
         boolean isAndroid = false;

         if (classesDir.exists()) {
            File[] files = classesDir.listFiles();
            if (files != null) {
               for (File file : files) {
                  classPathFiles.add(file);
               }
            }
         } else {
            isAndroid = true;
            if (flavor == null) {
               classesDir = new File(gradleBuildDir, "intermediates/classes/release/");
               if (!classesDir.exists()) {
                  throw new ConfigurationException(
                        "Please, select one flavour with the command: walkmod add-provider -Dflavor=\"YOUR_FLAVOR\" gradle");
               }
               classPathFiles.add(classesDir);
            } else {

               classesDir = new File(gradleBuildDir, "intermediates/classes/" + flavor + "/release/");
               if (!classesDir.exists()) {
                  throw new ConfigurationException("The flavor :[" + flavor + "] does not exist in ["
                        + classesDir.getAbsolutePath() + "]. Please, select a valid one");
               }
               classPathFiles.add(classesDir);
            }
         }

         if (isAndroid) {

            List<String> coordinates = getDepsCoordinates(connection);

            Integer version = getCompileAndroidSDKVersion(project.getGradleProject().getProjectDirectory());

            if (version != null) {
               File jar = getAndroidJar(version);
               if (jar != null && jar.exists()) {
                  classPathFiles.add(jar);
               }
            }

            if (!coordinates.isEmpty()) {
               classPathFiles.addAll(resolveArtifacts(coordinates));
            }
            if (localLibs != null) {
               try {
                  Iterator<Object> it = localLibs.iterator();
                  while (it.hasNext()) {
                     File auxLibs = new File(it.next().toString()).getCanonicalFile();
                     if (auxLibs.exists()) {
                        if (auxLibs.isDirectory()) {
                           File[] files = auxLibs.listFiles();
                           for (File jar : files) {
                              classPathFiles.add(jar);
                           }
                        } else {
                           classPathFiles.add(auxLibs);
                        }
                     }
                  }
               } catch (IOException e) {
                  throw new ConfigurationException("Error resolving the libs directories", e);
               }
            }

         } else {
            for (ExternalDependency externalDependency : project.getClasspath()) {
               classPathFiles.add(externalDependency.getFile());
            }

            DomainObjectSet<? extends EclipseProjectDependency> modules = project.getProjectDependencies();
            if (modules != null) {
               Iterator<? extends EclipseProjectDependency> it = modules.iterator();
               while (it.hasNext()) {
                  EclipseProjectDependency current = it.next();
                  ClassLoaderConfigurationProvider prov = new ClassLoaderConfigurationProvider();
                  prov.setWorkingDirectory(current.getTargetProject().getProjectDirectory().getAbsolutePath());
                  classPathFiles.addAll(prov.getClassPathFiles());
               }
            }

         }

      } finally {
         // Clean up
         connection.close();
      }
      return new LinkedList<File>(classPathFiles);
   }

   private void unzipAAR(String zipFilePath, String destDirectory) throws IOException {
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

   /**
    * Extracts a zip entry (file entry)
    *
    * @param zipIn
    * @param filePath
    * @throws IOException
    */
   private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
      BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
      byte[] bytesIn = new byte[4096];
      int read = 0;
      while ((read = zipIn.read(bytesIn)) != -1) {
         bos.write(bytesIn, 0, read);
      }
      bos.close();
   }

   public Collection<File> resolveArtifacts(List<String> coordinates) throws ConfigurationException {
      Collection<File> result = new LinkedList<File>();
      if (coordinates != null) {
         File aux = new File(userHomeDir, "caches/modules-2/files-2.1");
         for (String coordinate : coordinates) {
            String[] parts = coordinate.split(":");
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
                     repo = new File(androidHomeFile, "extras/google/m2repository");
                  } else {
                     repo = new File(androidHomeFile, "extras/android/m2repository");
                  }
                  groupIdDir = new File(repo, parts[0].replaceAll("\\.", File.separator));
                  artifactIdDir = new File(groupIdDir, parts[1]);
                  versionDir = new File(artifactIdDir, parts[2]);
                  File aarFile = new File(versionDir, parts[1] + "-" + parts[2] + ".aar");
                  if (aarFile.exists()) {
                     try {
                        File jarClasses = new File(versionDir, "classes.jar");
                        //if (!jarClasses.exists()) {
                        unzipAAR(aarFile.getAbsolutePath(), versionDir.getAbsolutePath());
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
                        throw new ConfigurationException("Error extracting the aar file " + aarFile.getAbsolutePath(),
                              e);
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
                                 unzipAAR(file.getAbsolutePath(), parentDir.getAbsolutePath());
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
      return result;
   }

   @Override
   public void load() throws ConfigurationException {
      if (configuration != null) {

         try {
            compile();
         } catch (Exception e1) {
            throw new ConfigurationException("Error compiling the project", e1.getCause());
         }
         List<File> classPathList = getClassPathFiles();
         String[] bootPath = System.getProperties().get("sun.boot.class.path").toString()
               .split(Character.toString(File.pathSeparatorChar));
         URL[] classPath = new URL[classPathList.size() + bootPath.length];

         int i = 0;
         for (String lib : bootPath) {

            try {
               classPath[i] = new File(lib).toURI().toURL();
            } catch (MalformedURLException e) {
               throw new ConfigurationException("Invalid URL for the boot classpath entry " + lib, e.getCause());
            }

            i++;
         }

         if (!classPathList.isEmpty()) {

            for (File entry : classPathList) {
               try {
                  classPath[i] = entry.toURI().toURL();
               } catch (MalformedURLException e) {
                  throw new ConfigurationException("Invalid URL for the dependency " + entry.getAbsolutePath(),
                        e.getCause());
               }
               i++;
            }

         }
         URLClassLoader loader = new URLClassLoader(classPath) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
               Class<?> result = null;
               try {
                  result = findClass(name);

               } catch (Throwable e) {

               }
               if (result != null) {
                  return result;
               }

               return super.loadClass(name, resolve);
            }

            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
               return loadClass(name, false);
            }
         };
         configuration.getParameters().put("classLoader", loader);

      }
   }

}
