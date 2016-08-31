package org.jetbrains.plugins.innerbuilder;

import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.jetbrains.plugins.innerbuilder.InnerBuilderUtils.hasLowerCaseChar;

public final class InnerBuilderCollector {

    private InnerBuilderCollector() {
    }

    @Nullable
    public static List<PsiFieldMember> collectFields(PsiFile file, Editor editor) {
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null) {
            return null;
        }

        PsiClass clazz = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        if (clazz == null || clazz.hasModifierProperty(PsiModifier.ABSTRACT)) {
            return null;
        }

        List<PsiFieldMember> allFields = new ArrayList<>();

        PsiClass classToExtractFieldsFrom = clazz;
        while (classToExtractFieldsFrom != null) {
            if (classToExtractFieldsFrom.hasModifierProperty(PsiModifier.STATIC)) {
                break;
            }

            List<PsiFieldMember> classFieldMembers = collectFieldsInClass(element, clazz, classToExtractFieldsFrom);
            allFields.addAll(0, classFieldMembers);

            classToExtractFieldsFrom = classToExtractFieldsFrom.getSuperClass();
        }

        return allFields;
    }

    private static List<PsiFieldMember> collectFieldsInClass(PsiElement element, PsiClass accessObjectClass,
                                                             PsiClass clazz) {
        List<PsiFieldMember> classFieldMembers = new ArrayList<>();
        PsiResolveHelper helper = JavaPsiFacade.getInstance(clazz.getProject()).getResolveHelper();

        for (PsiField field : clazz.getFields()) {

            // check access to the field from the builder container class (eg. private superclass fields)
            if (helper.isAccessible(field, accessObjectClass, clazz)
                    && !PsiTreeUtil.isAncestor(field, element, false)) {

                // skip static fields
                if (field.hasModifierProperty(PsiModifier.STATIC)) {
                    continue;
                }

                // skip any uppercase fields
                if (!hasLowerCaseChar(field.getName())) {
                    continue;
                }

                // skip eventual logging fields
                String fieldType = field.getType().getCanonicalText();
                if ("org.apache.log4j.Logger".equals(fieldType) || "org.apache.logging.log4j.Logger".equals(fieldType)
                        || "java.util.logging.Logger".equals(fieldType) || "org.slf4j.Logger".equals(fieldType)
                        || "ch.qos.logback.classic.Logger".equals(fieldType)
                        || "net.sf.microlog.core.Logger".equals(fieldType)
                        || "org.apache.commons.logging.Log".equals(fieldType)
                        || "org.pmw.tinylog.Logger".equals(fieldType) || "org.jboss.logging.Logger".equals(fieldType)
                        || "jodd.log.Logger".equals(fieldType)) {
                    continue;
                }

                if (field.hasModifierProperty(PsiModifier.FINAL)) {
                    if (field.getInitializer() != null) {
                        continue; // skip final fields that are assigned in the declaration
                    }

                    if (!accessObjectClass.isEquivalentTo(clazz)) {
                        continue; // skip final superclass fields
                    }
                }

                PsiClass containingClass = field.getContainingClass();
                if (containingClass != null) {
                    classFieldMembers.add(buildFieldMember(field, containingClass, clazz));
                }
            }
        }

        return classFieldMembers;
    }

    private static PsiFieldMember buildFieldMember(PsiField field, PsiClass containingClass,
                                                   PsiClass clazz) {
        return new PsiFieldMember(field,
                TypeConversionUtil.getSuperClassSubstitutor(containingClass, clazz, PsiSubstitutor.EMPTY));
    }
}
