package de.escalon.xml.xjc;

import com.sun.codemodel.JClass;
import com.sun.codemodel.JDefinedClass;
import com.sun.tools.xjc.outline.Outline;

public class OutlineHelper {

    public static JDefinedClass getJDefinedClassFromOutline(Outline outline, String fqcn) {
        JDefinedClass clazz = outline.getCodeModel()
            ._getClass(fqcn);
        return clazz;
    }
    
    /**
     * Gets existing class or dummy JDirectClass if fqcn is not part of the codemodel
     * @param outline to look into
     * @param fqcn to find
     * @return existing or dummy class
     */
    public static JClass getJClassFromOutline(Outline outline, String fqcn) {
        return outline.getCodeModel().ref(fqcn);
    }
    
}
