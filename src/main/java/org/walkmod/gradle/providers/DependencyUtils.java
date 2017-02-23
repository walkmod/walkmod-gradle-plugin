package org.walkmod.gradle.providers;

import java.io.File;

public class DependencyUtils {

    public String resolvePath(String groupId){
       return groupId.replaceAll("\\.", getFileSeparator());
    }
    
    
    public String getFileSeparator(){
        if(File.separatorChar == '\\'){
            return "\\\\";
        }
        return File.separator;
    }
    
}
