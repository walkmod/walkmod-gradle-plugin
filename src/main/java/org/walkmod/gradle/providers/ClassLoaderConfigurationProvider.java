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
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
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

	public GradleConnector getConnector() {
		if (connector == null) {
			connector = GradleConnector.newConnector();
			if (installationDir != null) {
				connector.useInstallation(new File(installationDir));
				if (userHomeDir != null) {
					connector.useGradleUserHomeDir(new File(userHomeDir));
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
			launcher.forTasks("compileJava", "compileTestJava");
			launcher.setStandardOutput(System.out);
			launcher.setStandardError(System.err);

			// Run the build
			launcher.run();
		} finally {
			// Clean up
			connection.close();
		}
	}

	public List<File> getClassPathFiles() {
		ProjectConnection connection = getConnector().connect();
		List<File> classPathFiles = new LinkedList<File>();
		try {
			// Load the Eclipse model for the project
			EclipseProject project = connection.getModel(EclipseProject.class);
			File gradleBuildDir = project.getGradleProject()
					.getBuildDirectory();
			File classesDir = new File(gradleBuildDir, buildDir);
			
			File[] files = classesDir.listFiles();
			if(files != null){
				for(File file: files){
					classPathFiles.add(file);
				}
			}

			for (ExternalDependency externalDependency : project.getClasspath()) {
				classPathFiles.add(externalDependency.getFile());
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
				throw new ConfigurationException(e1.getMessage());
			}
			List<File> classPathList = getClassPathFiles();
			if (!classPathList.isEmpty()) {
				URL[] classPath = new URL[classPathList.size()];
				int i = 0;
				for (File entry : classPathList) {
					try {
						classPath[i] = entry.toURI().toURL();
					} catch (MalformedURLException e) {
						throw new ConfigurationException(
								"Invalid URL for the dependency "
										+ entry.getAbsolutePath(), e.getCause());
					}
					i++;
				}
				URLClassLoader loader = new URLClassLoader(classPath);
				configuration.getParameters().put("classLoader", loader);
			}

		}
	}

}
