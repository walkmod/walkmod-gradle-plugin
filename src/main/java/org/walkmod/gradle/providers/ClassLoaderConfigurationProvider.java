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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedList;
import java.util.List;

import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.jboss.shrinkwrap.resolver.api.Resolvers;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolverSystem;
import org.jboss.shrinkwrap.resolver.impl.maven.MavenResolverSystemImpl;
import org.walkmod.conf.ConfigurationException;
import org.walkmod.conf.ConfigurationProvider;
import org.walkmod.conf.entities.Configuration;

public class ClassLoaderConfigurationProvider implements ConfigurationProvider {

   private File pomFile = null;

   private String buildDir = "classes";

   private Configuration configuration;

   private String installationDir;

   private String userHomeDir;

   private String workingDirectory;

   private GradleConnector connector = null;

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

   public void setBuildDir(String buildDir) {
      this.buildDir = buildDir;
   }

   public File getPomFile() {
      return pomFile;
   }

   public void setPomFile(File file) {
      this.pomFile = file;
   }

   @Override
   public void init(Configuration configuration) {
      this.configuration = configuration;
   }

   public GradleConnector getConnector() throws ConfigurationException {
      if (connector == null) {
         connector = GradleConnector.newConnector();
         if (installationDir != null) {
            connector.useInstallation(new File(installationDir));
            if (userHomeDir != null) {
               connector.useGradleUserHomeDir(new File(userHomeDir));
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
         launcher.forTasks("assemble");
         launcher.setStandardOutput(System.out);
         launcher.setStandardError(System.err);

         // Run the build
         launcher.run();
      } finally {
         // Clean up
         connection.close();
      }
   }

   public List<File> getClassPathFiles() throws ConfigurationException {
      ProjectConnection connection = getConnector().connect();
      List<File> classPathFiles = new LinkedList<File>();
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
            classesDir = new File(gradleBuildDir, "intermediates/classes/release/");
            classPathFiles.add(classesDir);
         }

         if (isAndroid) {

            BuildLauncher launcher = connection.newBuild();
            launcher.forTasks("dependencies");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            launcher.setStandardOutput(bos);
            launcher.setStandardError(System.err);

            String content = bos.toString();
            String[] lines = content.split("\\n");
            List<String> coordinates = new LinkedList<String>();
            for (int i = 0; i < lines.length; i++) {
               if (lines[i].startsWith("compile") || lines[i].startsWith("provided")) {
                  if (i + 1 < lines.length && !lines[i+1].startsWith("No dependencies")) {
                     int j = i + 1;
                     String prefix = null;
                     String nextGoal = "debugApk";
                     if(lines[i].startsWith("provided")){
                        nextGoal = "releaseApk";
                     }
                     while (!lines[j].startsWith(nextGoal)) {
                        if (prefix == null) {
                           int index = lines[j].lastIndexOf("--");
                           if (index != -1) {
                              prefix = lines[j].substring(0, index + 1);
                           }
                        }
                        coordinates.add(lines[j].substring(prefix.length()).trim());
                     }
                  }
               }
            }
            if (!coordinates.isEmpty()) {

               ClassLoader cl = Thread.currentThread().getContextClassLoader();
               if (configuration.getClassLoader() != null) {
                  cl = configuration.getClassLoader();
               }

               MavenResolverSystemImpl mrs = (MavenResolverSystemImpl) Resolvers.use(MavenResolverSystem.class, cl);
               mrs.getMavenWorkingSession().setOffline(true);

               MavenResolvedArtifact[] artifacts = mrs.resolve(coordinates).withTransitivity().asResolvedArtifact();
               for (int i = 0; i < artifacts.length; i++) {
                  classPathFiles.add(artifacts[i].asFile());
               }

            }
         } else {
            for (ExternalDependency externalDependency : project.getClasspath()) {
               classPathFiles.add(externalDependency.getFile());
            }
         }

      } finally {
         // Clean up
         connection.close();
      }
      return classPathFiles;
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
