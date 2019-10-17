package org.apache.camel.lsp.groovy;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

public class CamelScriptCompilationCustomizer extends CompilationCustomizer {
    public CamelScriptCompilationCustomizer(){
        this(CompilePhase.CANONICALIZATION);
    }

    public CamelScriptCompilationCustomizer(CompilePhase phase) {
        super(phase);
    }

    @Override
    public void call(SourceUnit sourceUnit, GeneratorContext generatorContext, ClassNode classNode) throws CompilationFailedException {
        ClassNode traitClassNode = null;
        if (classNode.getSuperClass().getName().equals("groovy.lang.Script")){
            try {
                ClassLoader cl = generatorContext.getCompileUnit().getClassLoader();
                Class<?> clazz = cl.loadClass("org.apache.camel.k.loader.groovy.dsl.IntegrationConfiguration");
                traitClassNode = new ClassNode(clazz);
                for(MethodNode method : traitClassNode.getMethods()){
                    classNode.addMethod(method);
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

    }
}
