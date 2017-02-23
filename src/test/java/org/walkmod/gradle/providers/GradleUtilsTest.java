package org.walkmod.gradle.providers;

import org.junit.Assert;
import org.junit.Test;

public class GradleUtilsTest {
    
    @Test
    public void testSimpleVersionNumbers(){
        
        GradleUtils utils = new GradleUtils();
        Assert.assertEquals(23, utils.getAndroidVersion("23"));
    
    }
    
    @Test
    public void testLabeledVersions(){
        
        GradleUtils utils = new GradleUtils();
        Assert.assertEquals(23, utils.getAndroidVersion("'android 23'"));
    
    }
    
    @Test
    public void testOnWindows(){
        GradleUtils du = new GradleUtils(){
            public String getFileSeparator(){
                return "\\\\";
            }
        };
        
        String path = du.resolvePath("org.walkmod.plugins");
        
        Assert.assertNotNull(path);
    }
    
    @Test
    public void testOnLinux(){
        GradleUtils du = new GradleUtils(){
            public String getFileSeparator(){
                return "/";
            }
        };
        
        String path = du.resolvePath("org.walkmod.plugins");
        
        Assert.assertNotNull(path);
    }

}
