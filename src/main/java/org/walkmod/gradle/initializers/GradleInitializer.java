package org.walkmod.gradle.initializers;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.gradle.BasicGradleProject;
import org.gradle.tooling.model.gradle.GradleBuild;
import org.walkmod.conf.Initializer;
import org.walkmod.conf.ProjectConfigurationProvider;

public class GradleInitializer implements Initializer {

	@Override
	public void execute(ProjectConfigurationProvider provider) throws Exception {
		execute(provider, null);
	}

	public void execute(ProjectConfigurationProvider provider, BasicGradleProject parent) throws Exception {
		DomainObjectSet<? extends BasicGradleProject> subprojects = null;
		File parentDir = provider.getConfigurationFile().getCanonicalFile().getParentFile();

		if (parent == null) {
			GradleConnector connector = GradleConnector.newConnector();

			connector.forProjectDirectory(parentDir);
			ProjectConnection connection = connector.connect();

			GradleBuild settings = connection.getModel(GradleBuild.class);
			subprojects = settings.getProjects();
		} else {
			subprojects = parent.getChildren();
		}
		Iterator<? extends BasicGradleProject> it = subprojects.iterator();
		String currentDir = parentDir.getCanonicalPath();
		List<String> modules = new LinkedList<String>();
		while (it.hasNext()) {
			BasicGradleProject current = it.next();
			File projectDir = current.getProjectDirectory().getCanonicalFile();
			if (!projectDir.getPath().equals(currentDir)) {
				modules.add(current.getName());
				ProjectConfigurationProvider moduleCfgProvider = provider
						.clone(new File(projectDir, "walkmod." + provider.getFileExtension()));

				execute(moduleCfgProvider, current);
			}

		}
		if (!modules.isEmpty()) {
			provider.addModules(modules);
		} else {
			provider.createConfig();
		}
	}

}
