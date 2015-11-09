package org.walkmod.gradle.initializers;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;
import org.walkmod.conf.entities.Configuration;
import org.walkmod.conf.providers.XMLConfigurationProvider;

public class GradleInitializerTest {

	@Test
	public void testGradleInitialization() throws Exception {
		File workDir = new File("src/test/resources/multiple");
		File file = new File(workDir, "walkmod.xml");
		File child1 = new File(new File(workDir, "bluewhale"), "walkmod.xml");
		File child2 = new File(new File(workDir, "krill"), "walkmod.xml");

		if (file.exists()) {
			file.delete();
		}
		if (child1.exists()) {
			child1.delete();
		}
		if (child2.exists()) {
			child2.delete();
		}

		GradleInitializer initializer = new GradleInitializer();
		XMLConfigurationProvider provider = new XMLConfigurationProvider(
				workDir.getPath() + File.separator + "walkmod.xml", false);
		initializer.execute(provider);

		Assert.assertTrue(file.exists());

		provider.load();
		Configuration conf = provider.getConfiguration();

		Assert.assertEquals(2, conf.getModules().size());

		Assert.assertTrue(child1.exists());

		Assert.assertTrue(child2.exists());

		file.delete();
		child1.delete();
		child2.delete();

	}
}
