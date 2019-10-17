////////////////////////////////////////////////////////////////////////////////
// Copyright 2019 Prominic.NET, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Prominic.NET, Inc.
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package net.prominic.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.control.CompilationUnit;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

public class CompletionProvider {
    private ASTNodeVisitor ast;
    private CompilationUnit compilationUnit;

    public CompletionProvider(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> provideCompletion(
            TextDocumentIdentifier textDocument, Position position, CompletionContext context) {
        if (ast == null) {
            //this shouldn't happen, but let's avoid an exception if something
            //goes terribly wrong.
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
        }
        URI uri = URI.create(textDocument.getUri());
        ASTNode offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if (offsetNode == null) {
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
        }
        ASTNode parentNode = ast.getParent(offsetNode);

        List<CompletionItem> items = new ArrayList<>();

        if (offsetNode instanceof PropertyExpression) {
            populateItemsFromPropertyExpression((PropertyExpression) offsetNode, position, items);
        } else if (parentNode instanceof PropertyExpression) {
            populateItemsFromPropertyExpression((PropertyExpression) parentNode, position, items);
        } else if (offsetNode instanceof MethodCallExpression) {
            populateItemsFromMethodCallExpression((MethodCallExpression) offsetNode, position, items);
        } else if (parentNode instanceof MethodCallExpression) {
            populateItemsFromMethodCallExpression((MethodCallExpression) parentNode, position, items);
        } else if (offsetNode instanceof VariableExpression) {
            populateItemsFromVariableExpression((VariableExpression) offsetNode, position, items);
        } else if (offsetNode instanceof ClosureExpression) {
            populateItemsFromClosureExpression((ClosureExpression) offsetNode, position, items);
        } else if (offsetNode instanceof ImportNode) {
            populateItemsFromImportExpression((ImportNode) offsetNode, position, items);
        }

        return CompletableFuture.completedFuture(Either.forLeft(items));
    }

    private void populateItemsFromPropertyExpression(PropertyExpression propExpr, Position position,
                                                     List<CompletionItem> items) {
        Range propertyRange = GroovyLanguageServerUtils.astNodeToRange(propExpr.getProperty());
        String memberName = getMemberName(propExpr.getPropertyAsString(), propertyRange, position);
        populateItemsFromExpression(propExpr.getObjectExpression(), memberName, items);
    }

    private void populateItemsFromMethodCallExpression(MethodCallExpression methodCallExpr, Position position,
                                                       List<CompletionItem> items) {
        Range methodRange = GroovyLanguageServerUtils.astNodeToRange(methodCallExpr.getMethod());
        String memberName = getMemberName(methodCallExpr.getMethodAsString(), methodRange, position);
        populateItemsFromExpression(methodCallExpr.getObjectExpression(), memberName, items);
    }

    private void populateItemsFromImportExpression(ImportNode importNode, Position position,
                                                   List<CompletionItem> items) {
        String packageName;
        if (importNode.getClassName() != null) {
            packageName = importNode.getClassName().substring(0, importNode.getClassName().lastIndexOf('.'));
        } else {
            packageName = importNode.getPackageName();
        }

        List<ClassLoader> classLoadersList = new ArrayList<>();
        classLoadersList.add(ClasspathHelper.contextClassLoader());
        classLoadersList.add(ClasspathHelper.staticClassLoader());
        classLoadersList.add(this.compilationUnit.getClassLoader());

        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setScanners(new SubTypesScanner(false /* don't exclude Object.class */))
                .setUrls(ClasspathHelper.forClassLoader(classLoadersList.toArray(new ClassLoader[0])))
                .filterInputsBy(new FilterBuilder().include(FilterBuilder.prefix(packageName))));

        Set<String> allTypes = reflections.getAllTypes();
        List<CompletionItem> collect = allTypes.stream()
                .map(pak -> {
                    return pak.substring(pak.indexOf(packageName) + packageName.length() + 1);
                })

                .map(pak -> {
                    CompletionItem item = new CompletionItem();
                    item.setLabel(pak);
                    item.setKind(CompletionItemKind.Class);
                    return item;
                }).collect(Collectors.toList());
        items.addAll(collect);
    }

    private void populateItemsFromVariableExpression(VariableExpression varExpr, Position position, List<CompletionItem> items) {
        Range varRange = GroovyLanguageServerUtils.astNodeToRange(varExpr);
        String memberName = getMemberName(varExpr.getName(), varRange, position);
        ClassNode enclosingClass = GroovyASTUtils.getEnclosingClass(varExpr, ast);
        populateItemsFromPropertiesAndFields(enclosingClass.getProperties(), enclosingClass.getFields(), memberName,
                items);
        delegateScriptContextToObject(enclosingClass, items, "org.apache.camel.k.loader.groovy.dsl.IntegrationConfiguration");
        populateItemsFromMethods(enclosingClass.getMethods(), memberName, items);
        decorateLabels(items);
    }

    private void delegateScriptContextToObject(ClassNode enclosingClass, List<CompletionItem> items, String className) {
        if (enclosingClass.getSuperClass().getName().equals("groovy.lang.Script")) {
            populateItemsFromMethods(extractMethodsFromClass(className), "", items);
        }
    }

    private List<MethodNode> extractMethodsFromClass(String className) {
        ClassLoader cl = this.compilationUnit.getClassLoader();
        Class<?> clazz = null;
        try {
            clazz = cl.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        ClassNode traitClassNode = new ClassNode(clazz);
        return traitClassNode.getMethods()
                .stream()
                .filter(m -> !m.getName().startsWith("$"))
                .collect(Collectors.toList());
    }

    private void populateItemsFromClosureExpression(ClosureExpression closExpr, Position position, List<CompletionItem> items) {
        ASTNode parent = ast.getParent(ast.getParent(closExpr));
        if (parent instanceof MethodCallExpression) {
            MethodCallExpression methodExpression = (MethodCallExpression) parent;
            String name = methodExpression.getMethodAsString();
            List<MethodNode> methodNodes = new ArrayList<>();

            switch (name) {
                case "context":
                    methodNodes = extractMethodsFromClass("org.apache.camel.k.loader.groovy.dsl.ContextConfiguration");
                    break;
                case "registry":
                    methodNodes = extractMethodsFromClass("org.apache.camel.k.loader.groovy.dsl.RegistryConfiguration");
                    break;
                case "components":
                    methodNodes = extractMethodsFromClass("org.apache.camel.k.loader.groovy.dsl.ComponentsConfiguration");
                    break;
                case "beans":
                    methodNodes = extractMethodsFromClass("org.apache.camel.k.loader.groovy.dsl.BeansConfiguration");
                    break;
                case "rest":
                    methodNodes = extractMethodsFromClass("org.apache.camel.k.loader.groovy.dsl.RestConfiguration");
                    break;
            }

            populateItemsFromMethods(methodNodes, "", items);
            decorateLabels(items);
        }
    }

    private void decorateLabels(List<CompletionItem> items) {
        for (CompletionItem item : items) {
            if ("getMetaClass".equals(item.getLabel()) ||
                    "setMetaClass".equals(item.getLabel()) ||
                    "getProperty".equals(item.getLabel()) ||
                    "setProperty".equals(item.getLabel()) ||
                    "invokeMethod".equals(item.getLabel()) ||
                    "main".equals(item.getLabel()) ||
                    "run".equals(item.getLabel())
            ) {
                item.setInsertText(item.getLabel());
                item.setSortText("~" + item.getLabel());
                item.setKind(CompletionItemKind.Interface);
            }

        }
    }

    private void populateItemsFromPropertiesAndFields(List<PropertyNode> properties, List<FieldNode> fields,
                                                      String memberNamePrefix, List<CompletionItem> items) {
        Set<String> foundNames = new HashSet<>();
        List<CompletionItem> propItems = properties.stream().filter(property -> {
            String name = property.getName();
            //sometimes, a property and a field will have the same name
            if (name.startsWith(memberNamePrefix) && !foundNames.contains(name)) {
                foundNames.add(name);
                return true;
            }
            return false;
        }).map(property -> {
            CompletionItem item = new CompletionItem();
            item.setLabel(property.getName());
            item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(property));
            return item;
        }).collect(Collectors.toList());
        items.addAll(propItems);
        List<CompletionItem> fieldItems = fields.stream().filter(field -> {
            String name = field.getName();
            //sometimes, a property and a field will have the same name
            if (name.startsWith(memberNamePrefix) && !foundNames.contains(name)) {
                foundNames.add(name);
                return true;
            }
            return false;
        }).map(field -> {
            CompletionItem item = new CompletionItem();
            item.setLabel(field.getName());
            item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(field));
            return item;
        }).collect(Collectors.toList());
        items.addAll(fieldItems);
    }

    private void populateItemsFromMethods(List<MethodNode> methods, String memberNamePrefix,
                                          List<CompletionItem> items) {
        Set<String> foundMethods = new HashSet<>();
        List<CompletionItem> methodItems = methods.stream().filter(method -> {
            String methodName = method.getName();
            //overloads can cause duplicates
            if (methodName.startsWith(memberNamePrefix) && !foundMethods.contains(methodName)) {
                foundMethods.add(methodName);
                return true;
            }
            return false;
        }).map(method -> {
            CompletionItem item = new CompletionItem();
            item.setLabel(method.getName());
            item.setKind(GroovyLanguageServerUtils.astNodeToCompletionItemKind(method));
            return item;
        }).collect(Collectors.toList());
        items.addAll(methodItems);
    }

    private void populateItemsFromExpression(Expression leftSide, String memberNamePrefix, List<CompletionItem> items) {
        List<PropertyNode> properties = GroovyASTUtils.getPropertiesForLeftSideOfPropertyExpression(leftSide, ast);
        List<FieldNode> fields = GroovyASTUtils.getFieldsForLeftSideOfPropertyExpression(leftSide, ast);
        populateItemsFromPropertiesAndFields(properties, fields, memberNamePrefix, items);

        List<MethodNode> methods = GroovyASTUtils.getMethodsForLeftSideOfPropertyExpression(leftSide, ast);
        populateItemsFromMethods(methods, memberNamePrefix, items);
    }

    private String getMemberName(String memberName, Range range, Position position) {
        if (position.getLine() == range.getStart().getLine()
                && position.getCharacter() > range.getStart().getCharacter()) {
            int length = position.getCharacter() - range.getStart().getCharacter();
            if (length > 0 && length <= memberName.length()) {
                return memberName.substring(0, length);
            }
        }
        return "";
    }

    private String getPackage(String input, Range range, Position position) {
        if (position.getLine() == range.getStart().getLine()
                && position.getCharacter() > range.getStart().getCharacter()) {
            int length = position.getCharacter() - range.getStart().getCharacter();
            if (length > 0) {
                return input.substring(position.getLine() - length, length);
            }
        }
        return "";
    }

    public void setCompilationUnit(CompilationUnit unit) {
        this.compilationUnit = unit;
    }
}