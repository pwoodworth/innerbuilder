package org.jetbrains.plugins.innerbuilder;

import com.google.common.collect.ImmutableMap;
import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiUtil;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jetbrains.plugins.innerbuilder.InnerBuilderUtils.areTypesPresentableEqual;

public class InnerBuilderGenerator implements Runnable {

    @NonNls
    private static final String BUILDER_CLASS_NAME = "Builder";
    @NonNls
    private static final String BUILDER_SETTER_DEFAULT_PARAMETER_NAME = "val";
    @NonNls
    private static final String BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME = "value";
    @NonNls
    private static final String JSR305_NONNULL = "javax.annotation.Nonnull";
    @NonNls
    private static final String FINDBUGS_NONNULL = "edu.umd.cs.findbugs.annotations.NonNull";

    private final Project project;
    private final PsiFile file;
    private final Editor editor;
    private final List<PsiFieldMember> selectedFields;
    private final PsiElementFactory psiElementFactory;

    private InnerBuilderGenerator(Project project, PsiFile file, Editor editor, List<PsiFieldMember> selectedFields) {
        this.project = project;
        this.file = file;
        this.editor = editor;
        this.selectedFields = selectedFields;
        psiElementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
    }

    public static void generate(Project project, Editor editor, PsiFile file,
                                List<PsiFieldMember> selectedFields) {
        Runnable builderGenerator = new InnerBuilderGenerator(project, file, editor, selectedFields);
        ApplicationManager.getApplication().runWriteAction(builderGenerator);
    }

    private static EnumSet<InnerBuilderOption> currentOptions() {
//        EnumSet<InnerBuilderOption> options = EnumSet.noneOf(InnerBuilderOption.class);
        return EnumSet.copyOf(currentOptions2().keySet());
    }

    private static ImmutableMap<InnerBuilderOption, String> currentOptions2() {

        ImmutableMap.Builder<InnerBuilderOption, String> builder = ImmutableMap.builder();

        PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();

        for (InnerBuilderOption option : InnerBuilderOption.values()) {

            if (option.isTextfield()) {
                String currentSetting = propertiesComponent.getValue(option.getProperty(), "");
                if (StringUtils.isNotBlank(currentSetting)) {
                    builder.put(option, currentSetting);
                }
            } else {
                boolean currentSetting = propertiesComponent.getBoolean(option.getProperty(), false);
                if (currentSetting) {
                    builder.put(option, "true");
                }
            }
        }
        return builder.build();
    }

    @Override
    public void run() {
        PsiClass topLevelClass = InnerBuilderUtils.getTopLevelClass(project, file, editor);
        if (topLevelClass == null) {
            return;
        }
        ImmutableMap<InnerBuilderOption, String> fullOptions = currentOptions2();
        Set<InnerBuilderOption> options = fullOptions.keySet();
        PsiClass builderClass = findOrCreateBuilderClass(topLevelClass);
        PsiType builderType = psiElementFactory.createTypeFromText(BUILDER_CLASS_NAME, null);
        PsiMethod constructor = generateConstructor(topLevelClass, builderType);

        addMethod(topLevelClass, null, constructor, true);
        Collection<PsiFieldMember> finalFields = new ArrayList<>();
        Collection<PsiFieldMember> nonFinalFields = new ArrayList<>();

        PsiElement lastAddedField = null;
        for (PsiFieldMember fieldMember : selectedFields) {
            lastAddedField = findOrCreateField(builderClass, fieldMember, lastAddedField);
            if (fieldMember.getElement().hasModifierProperty(PsiModifier.FINAL)
                    && !options.contains(InnerBuilderOption.FINAL_SETTERS)) {
                finalFields.add(fieldMember);
                PsiUtil.setModifierProperty((PsiField) lastAddedField, PsiModifier.FINAL, true);
            } else {
                nonFinalFields.add(fieldMember);
            }
        }
        if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
            PsiMethod newBuilderMethod = generateNewBuilderMethod(builderType, finalFields, options);
            addMethod(topLevelClass, null, newBuilderMethod, false);
        }

        // builder constructor, accepting the final fields
        PsiMethod builderConstructorMethod = generateBuilderConstructor(builderClass, finalFields, options);
        addMethod(builderClass, null, builderConstructorMethod, false);

        // builder copy constructor or static copy method
        if (options.contains(InnerBuilderOption.COPY_CONSTRUCTOR)) {
            if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
                PsiMethod copyBuilderMethod = generateCopyBuilderMethod(topLevelClass, builderType,
                        nonFinalFields, options);
                addMethod(topLevelClass, null, copyBuilderMethod, true);
            } else {
                PsiMethod copyConstructorBuilderMethod = generateCopyConstructor(topLevelClass, builderType,
                        selectedFields, options);
                addMethod(builderClass, null, copyConstructorBuilderMethod, true);
            }
        }

        // builder methods
        PsiElement lastAddedElement = null;
        for (PsiFieldMember member : nonFinalFields) {
            PsiMethod setterMethod = generateBuilderSetter(builderType, member, fullOptions);
            lastAddedElement = addMethod(builderClass, lastAddedElement, setterMethod, false);
        }

        // builder.build() method
        PsiMethod buildMethod = generateBuildMethod(topLevelClass, options);
        addMethod(builderClass, lastAddedElement, buildMethod, false);

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(file);
        CodeStyleManager.getInstance(project).reformat(builderClass);
    }

    private PsiMethod generateCopyBuilderMethod(PsiClass topLevelClass, PsiType builderType,
                                                Collection<PsiFieldMember> fields,
                                                Set<InnerBuilderOption> options) {
        PsiMethod copyBuilderMethod = psiElementFactory.createMethod("newBuilder", builderType);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.PUBLIC, true);

        PsiType topLevelClassType = psiElementFactory.createType(topLevelClass);
        PsiParameter parameter = psiElementFactory.createParameter("copy", topLevelClassType);
        PsiModifierList parameterModifierList = parameter.getModifierList();

        if (parameterModifierList != null) {
            if (options.contains(InnerBuilderOption.JSR305_ANNOTATIONS)) {
                parameterModifierList.addAnnotation(JSR305_NONNULL);
            }
            if (options.contains(InnerBuilderOption.FINDBUGS_ANNOTATION)) {
                parameterModifierList.addAnnotation(FINDBUGS_NONNULL);
            }
        }
        copyBuilderMethod.getParameterList().add(parameter);
        PsiCodeBlock copyBuilderBody = copyBuilderMethod.getBody();
        if (copyBuilderBody != null) {
            StringBuilder copyBuilderParameters = new StringBuilder();
            for (PsiFieldMember fieldMember : selectedFields) {
                if (fieldMember.getElement().hasModifierProperty(PsiModifier.FINAL)
                        && !options.contains(InnerBuilderOption.FINAL_SETTERS)) {

                    if (copyBuilderParameters.length() > 0) {
                        copyBuilderParameters.append(", ");
                    }

                    copyBuilderParameters.append(String.format("copy.%s", fieldMember.getElement().getName()));
                }
            }
            if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
                PsiStatement newBuilderStatement = psiElementFactory.createStatementFromText(String.format(
                        "%s builder = new %s(%s);", builderType.getPresentableText(),
                        builderType.getPresentableText(), copyBuilderParameters.toString()),
                        copyBuilderMethod);
                copyBuilderBody.add(newBuilderStatement);

                addCopyBody(fields, copyBuilderMethod, "builder.");
                copyBuilderBody.add(psiElementFactory.createStatementFromText("return builder;", copyBuilderMethod));
            } else {
                PsiStatement newBuilderStatement = psiElementFactory.createStatementFromText(String.format(
                        "return new %s(%s);", builderType.getPresentableText(),
                        copyBuilderParameters.toString()),
                        copyBuilderMethod);
                copyBuilderBody.add(newBuilderStatement);
            }
        }
        return copyBuilderMethod;
    }

    private PsiMethod generateCopyConstructor(PsiClass topLevelClass, PsiType builderType,
                                              Collection<PsiFieldMember> nonFinalFields,
                                              Set<InnerBuilderOption> options) {

        PsiMethod copyConstructor = psiElementFactory.createConstructor(builderType.getPresentableText());
        PsiUtil.setModifierProperty(copyConstructor, PsiModifier.PUBLIC, true);

        PsiType topLevelClassType = psiElementFactory.createType(topLevelClass);
        PsiParameter constructorParameter = psiElementFactory.createParameter("copy", topLevelClassType);
        PsiModifierList parameterModifierList = constructorParameter.getModifierList();

        if (parameterModifierList != null) {
            if (options.contains(InnerBuilderOption.JSR305_ANNOTATIONS))
                parameterModifierList.addAnnotation(JSR305_NONNULL);
            if (options.contains(InnerBuilderOption.FINDBUGS_ANNOTATION))
                parameterModifierList.addAnnotation(FINDBUGS_NONNULL);
        }
        copyConstructor.getParameterList().add(constructorParameter);
        addCopyBody(nonFinalFields, copyConstructor, "this.");
        return copyConstructor;
    }

    private void addCopyBody(Collection<PsiFieldMember> fields, PsiMethod method, String qName) {
        PsiCodeBlock methodBody = method.getBody();
        if (methodBody == null) {
            return;
        }
        for (PsiFieldMember member : fields) {
            PsiField field = member.getElement();
            PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s%2$s = copy.%2$s;", qName, field.getName()), method);
            methodBody.add(assignStatement);
        }
    }

    private PsiMethod generateBuilderConstructor(PsiClass builderClass,
                                                 Collection<PsiFieldMember> finalFields,
                                                 Set<InnerBuilderOption> options) {

        PsiMethod builderConstructor = psiElementFactory.createConstructor(builderClass.getName());
        if (options.contains(InnerBuilderOption.NEW_BUILDER_METHOD)) {
            PsiUtil.setModifierProperty(builderConstructor, PsiModifier.PRIVATE, true);
        } else {
            PsiUtil.setModifierProperty(builderConstructor, PsiModifier.PUBLIC, true);
        }
        PsiCodeBlock builderConstructorBody = builderConstructor.getBody();
        if (builderConstructorBody != null) {
            for (PsiFieldMember member : finalFields) {
                PsiField field = member.getElement();
                PsiType fieldType = field.getType();
                String fieldName = field.getName();

                PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                PsiModifierList parameterModifierList = parameter.getModifierList();
                boolean useJsr305 = options.contains(InnerBuilderOption.JSR305_ANNOTATIONS);
                boolean useFindbugs = options.contains(InnerBuilderOption.FINDBUGS_ANNOTATION);

                if (!InnerBuilderUtils.isPrimitive(field) && parameterModifierList != null) {
                    if (useJsr305) parameterModifierList.addAnnotation(JSR305_NONNULL);
                    if (useFindbugs) parameterModifierList.addAnnotation(FINDBUGS_NONNULL);
                }

                builderConstructor.getParameterList().add(parameter);
                PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                        "this.%1$s = %1$s;", fieldName), builderConstructor);
                builderConstructorBody.add(assignStatement);
            }
        }

        return builderConstructor;
    }

    private PsiMethod generateNewBuilderMethod(PsiType builderType, Collection<PsiFieldMember> finalFields,
                                               Set<InnerBuilderOption> options) {
        PsiMethod newBuilderMethod = psiElementFactory.createMethod("newBuilder", builderType);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.PUBLIC, true);

        StringBuilder fieldList = new StringBuilder();
        if (!finalFields.isEmpty()) {
            for (PsiFieldMember member : finalFields) {
                PsiField field = member.getElement();
                PsiType fieldType = field.getType();
                String fieldName = field.getName();

                PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                PsiModifierList parameterModifierList = parameter.getModifierList();
                if (parameterModifierList != null) {

                    if (!InnerBuilderUtils.isPrimitive(field)) {
                        if (options.contains(InnerBuilderOption.JSR305_ANNOTATIONS))
                            parameterModifierList.addAnnotation(JSR305_NONNULL);
                        if (options.contains(InnerBuilderOption.FINDBUGS_ANNOTATION))
                            parameterModifierList.addAnnotation(FINDBUGS_NONNULL);
                    }
                }
                newBuilderMethod.getParameterList().add(parameter);
                if (fieldList.length() > 0) {
                    fieldList.append(", ");
                }
                fieldList.append(fieldName);
            }
        }
        PsiCodeBlock newBuilderMethodBody = newBuilderMethod.getBody();
        if (newBuilderMethodBody != null) {
            PsiStatement newStatement = psiElementFactory.createStatementFromText(String.format(
                    "return new %s(%s);", builderType.getPresentableText(), fieldList.toString()),
                    newBuilderMethod);
            newBuilderMethodBody.add(newStatement);
        }
        return newBuilderMethod;
    }

    private PsiMethod generateBuilderSetter(PsiType builderType, PsiFieldMember member, Map<InnerBuilderOption, String> fullOptions) {

        Set<InnerBuilderOption> options = fullOptions.keySet();
        PsiField field = member.getElement();
        PsiType fieldType = field.getType();
        String fieldName = field.getName();

        String methodName;
        if (options.contains(InnerBuilderOption.WITH_NOTATION)) {
            methodName = String.format("%s%s", fullOptions.get(InnerBuilderOption.WITH_NOTATION), InnerBuilderUtils.capitalize(fieldName));
        } else {
            methodName = fieldName;
        }

        String parameterName = options.contains(InnerBuilderOption.FIELD_NAMES) ?
                fieldName :
                !BUILDER_SETTER_DEFAULT_PARAMETER_NAME.equals(fieldName) ?
                        BUILDER_SETTER_DEFAULT_PARAMETER_NAME :
                        BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME;
        PsiMethod setterMethod = psiElementFactory.createMethod(methodName, builderType);
        boolean useJsr305 = options.contains(InnerBuilderOption.JSR305_ANNOTATIONS);
        boolean useFindbugs = options.contains(InnerBuilderOption.FINDBUGS_ANNOTATION);

        if (useJsr305) setterMethod.getModifierList().addAnnotation(JSR305_NONNULL);
        if (useFindbugs) setterMethod.getModifierList().addAnnotation(FINDBUGS_NONNULL);

        setterMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);
        PsiParameter setterParameter = psiElementFactory.createParameter(parameterName, fieldType);

        if (!(fieldType instanceof PsiPrimitiveType)) {
            PsiModifierList setterParameterModifierList = setterParameter.getModifierList();
            if (setterParameterModifierList != null) {
                if (useJsr305) setterParameterModifierList.addAnnotation(JSR305_NONNULL);
                if (useFindbugs) setterParameterModifierList.addAnnotation(FINDBUGS_NONNULL);
            }
        }
        setterMethod.getParameterList().add(setterParameter);
        PsiCodeBlock setterMethodBody = setterMethod.getBody();
        if (setterMethodBody != null) {
            String actualFieldName = options.contains(InnerBuilderOption.FIELD_NAMES) ?
                    "this." + fieldName :
                    fieldName;
            PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s = %s;", actualFieldName, parameterName), setterMethod);
            setterMethodBody.add(assignStatement);
            setterMethodBody.add(InnerBuilderUtils.createReturnThis(psiElementFactory, setterMethod));
        }
        setSetterComment(setterMethod, fieldName, parameterName);
        return setterMethod;
    }

    private PsiMethod generateConstructor(PsiClass topLevelClass, PsiType builderType) {
        PsiMethod constructor = psiElementFactory.createConstructor(topLevelClass.getName());
        constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true);

        PsiParameter builderParameter = psiElementFactory.createParameter("builder", builderType);
        constructor.getParameterList().add(builderParameter);

        PsiCodeBlock constructorBody = constructor.getBody();
        if (constructorBody != null) {
            for (PsiFieldMember member : selectedFields) {
                PsiField field = member.getElement();

                PsiMethod setterPrototype = PropertyUtil.generateSetterPrototype(field);
                PsiMethod setter = topLevelClass.findMethodBySignature(setterPrototype, true);

                String fieldName = field.getName();
                boolean isFinal = false;
                PsiModifierList modifierList = field.getModifierList();
                if (modifierList != null) {
                    isFinal = modifierList.hasModifierProperty(PsiModifier.FINAL);
                }

                String assignText;
                if (setter == null || isFinal) {
                    assignText = String.format("%1$s = builder.%1$s;", fieldName);
                } else {
                    assignText = String.format("%s(builder.%s);", setter.getName(), fieldName);
                }

                PsiStatement assignStatement = psiElementFactory.createStatementFromText(assignText, null);
                constructorBody.add(assignStatement);
            }
        }

        return constructor;
    }

    private PsiMethod generateBuildMethod(PsiClass topLevelClass, Set<InnerBuilderOption> options) {
        PsiType topLevelClassType = psiElementFactory.createType(topLevelClass);
        PsiMethod buildMethod = psiElementFactory.createMethod("build", topLevelClassType);

        boolean useJsr305 = options.contains(InnerBuilderOption.JSR305_ANNOTATIONS);
        boolean useFindbugs = options.contains(InnerBuilderOption.FINDBUGS_ANNOTATION);
        if (useJsr305)
            buildMethod.getModifierList().addAnnotation(JSR305_NONNULL);
        if (useFindbugs)
            buildMethod.getModifierList().addAnnotation(FINDBUGS_NONNULL);

        buildMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);

        PsiCodeBlock buildMethodBody = buildMethod.getBody();
        if (buildMethodBody != null) {
            PsiStatement returnStatement = psiElementFactory.createStatementFromText(String.format(
                    "return new %s(this);", topLevelClass.getName()), buildMethod);
            buildMethodBody.add(returnStatement);
        }
        setBuildMethodComment(buildMethod, topLevelClass);
        return buildMethod;
    }

    @NotNull
    private PsiClass findOrCreateBuilderClass(PsiClass topLevelClass) {
        PsiClass builderClass = topLevelClass.findInnerClassByName(BUILDER_CLASS_NAME, false);
        if (builderClass == null) {
            return createBuilderClass(topLevelClass);
        }

        return builderClass;
    }

    @NotNull
    private PsiClass createBuilderClass(PsiClass topLevelClass) {
        PsiClass builderClass = (PsiClass) topLevelClass.add(psiElementFactory.createClass(BUILDER_CLASS_NAME));
        PsiUtil.setModifierProperty(builderClass, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(builderClass, PsiModifier.FINAL, true);
        setBuilderComment(builderClass, topLevelClass);
        return builderClass;
    }

    private PsiElement findOrCreateField(PsiClass builderClass, PsiFieldMember member,
                                         @Nullable PsiElement last) {
        PsiField field = member.getElement();
        String fieldName = field.getName();
        PsiType fieldType = field.getType();
        PsiField existingField = builderClass.findFieldByName(fieldName, false);
        if (existingField == null || !areTypesPresentableEqual(existingField.getType(), fieldType)) {
            if (existingField != null) {
                existingField.delete();
            }
            PsiField newField = psiElementFactory.createField(fieldName, fieldType);
            if (last != null) {
                return builderClass.addAfter(newField, last);
            } else {
                return builderClass.add(newField);
            }
        }
        return existingField;
    }

    private PsiElement addMethod(@NotNull PsiClass target, @Nullable PsiElement after,
                                 @NotNull PsiMethod newMethod, boolean replace) {
        PsiMethod existingMethod = target.findMethodBySignature(newMethod, false);
        if (existingMethod == null && newMethod.isConstructor()) {
            for (PsiMethod constructor : target.getConstructors()) {
                if (InnerBuilderUtils.areParameterListsEqual(constructor.getParameterList(),
                        newMethod.getParameterList())) {
                    existingMethod = constructor;
                    break;
                }
            }
        }
        if (existingMethod == null) {
            if (after != null) {
                return target.addAfter(newMethod, after);
            } else {
                return target.add(newMethod);
            }
        } else if (replace) {
            existingMethod.replace(newMethod);
        }
        return existingMethod;
    }

    private void setBuilderComment(PsiClass clazz, PsiClass topLevelClass) {
        if (currentOptions().contains(InnerBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n").append("* {@code ");
            str.append(topLevelClass.getName()).append("} builder static inner class.\n");
            str.append("*/");
            setStringComment(clazz, str.toString());
        }
    }

    private void setSetterComment(PsiMethod method, String fieldName, String parameterName) {
        if (currentOptions().contains(InnerBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n").append("* Sets the {@code ").append(fieldName);
            str.append("} and returns a reference to this Builder so that the methods can be chained together.\n");
            str.append("* @param ").append(parameterName).append(" the {@code ");
            str.append(fieldName).append("} to set\n");
            str.append("* @return a reference to this Builder\n*/");
            setStringComment(method, str.toString());
        }
    }

    private void setBuildMethodComment(PsiMethod method, PsiClass topLevelClass) {
        if (currentOptions().contains(InnerBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n");
            str.append("* Returns a {@code ").append(topLevelClass.getName()).append("} built ");
            str.append("from the parameters previously set.\n*\n");
            str.append("* @return a {@code ").append(topLevelClass.getName()).append("} ");
            str.append("built with parameters of this {@code ").append(topLevelClass.getName()).append(".Builder}\n*/");
            setStringComment(method, str.toString());
        }
    }

    private void setStringComment(PsiMethod method, String strComment) {
        PsiComment comment = psiElementFactory.createCommentFromText(strComment, null);
        PsiDocComment doc = method.getDocComment();
        if (doc != null) {
            doc.replace(comment);
        } else {
            method.addBefore(comment, method.getFirstChild());
        }
    }

    private void setStringComment(PsiClass clazz, String strComment) {
        PsiComment comment = psiElementFactory.createCommentFromText(strComment, null);
        PsiDocComment doc = clazz.getDocComment();
        if (doc != null) {
            doc.replace(comment);
        } else {
            clazz.addBefore(comment, clazz.getFirstChild());
        }
    }
}
