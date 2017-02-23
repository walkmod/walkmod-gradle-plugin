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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

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
                GradleUtils utils = new GradleUtils();
                List<String> coordinates = utils.getDepsCoordinates(connection);
              
                Integer version = utils.getCompileAndroidSDKVersion(project.getGradleProject().getProjectDirectory());

                if (version != null) {
                    File jar = utils.getAndroidJar(version);
                    if (jar != null && jar.exists()) {
                        classPathFiles.add(jar);
                    }
                }

                if (!coordinates.isEmpty()) {
                    classPathFiles.addAll(utils.resolveArtifacts(userHomeDir,coordinates));
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
