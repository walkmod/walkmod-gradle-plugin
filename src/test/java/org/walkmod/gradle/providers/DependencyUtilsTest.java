package org.walkmod.gradle.providers;

import org.junit.Assert;
import org.junit.Test;


public class DependencyUtilsTest {

    @Test
    public void testOnWindows(){
        DependencyUtils du = new DependencyUtils(){
            public String getFileSeparator(){
                return "\\\\";
            }
        };
        
        String path = du.resolvePath("org.walkmod.plugins");
        
        Assert.assertNotNull(path);
    }
    
    @Test
    public void testOnLinux(){
        DependencyUtils du = new DependencyUtils(){
            public String getFileSeparator(){
                return "/";
            }
        };
        
        String path = du.resolvePath("org.walkmod.plugins");
        
        Assert.assertNotNull(path);
    }
}
