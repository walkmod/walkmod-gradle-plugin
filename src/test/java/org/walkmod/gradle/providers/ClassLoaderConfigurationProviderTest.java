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
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.walkmod.conf.entities.impl.ConfigurationImpl;

public class ClassLoaderConfigurationProviderTest {
	
	
	@Test
	public void testResolveShouldCompile() throws Exception {
		ClassLoaderConfigurationProvider prov = new ClassLoaderConfigurationProvider();
		prov.setWorkingDirectory("src/test/resources/project-sample");
		prov.compile();
	}
	
	@Test
	public void testResolveShouldRetrieveGradleDependencies() throws Exception {
		ClassLoaderConfigurationProvider prov = new ClassLoaderConfigurationProvider();
		prov.setWorkingDirectory("src/test/resources/project-sample");
		List<File> classPath = prov.getClassPathFiles();
		Assert.assertTrue(classPath.size() > 0);
	}
	
	@Test
	public void testConfigurationUpdate() throws Exception{
		ClassLoaderConfigurationProvider prov = new ClassLoaderConfigurationProvider();
		prov.setWorkingDirectory("src/test/resources/project-sample");
		ConfigurationImpl conf = new ConfigurationImpl();
		prov.init(conf);
		prov.load();
		ClassLoader cl = (ClassLoader)conf.getParameters().get("classLoader");
		Assert.assertNotNull(cl);
		cl.loadClass("org.gradle.sample.Main");
	}

}
