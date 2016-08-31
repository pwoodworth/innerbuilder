package org.jetbrains.plugins.innerbuilder;

import com.intellij.codeInsight.CodeInsightUtilBase;
import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.lang.LanguageCodeInsightActionHandler;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.jetbrains.plugins.innerbuilder.InnerBuilderCollector.collectFields;
import static org.jetbrains.plugins.innerbuilder.InnerBuilderOptionSelector.selectFieldsAndOptions;

public class InnerBuilderHandler implements LanguageCodeInsightActionHandler {

    @Override
    public boolean isValidFor(Editor editor, PsiFile file) {
        if (!(file instanceof PsiJavaFile)) {
            return false;
        }

        Project project = editor.getProject();
        if (project == null) {
            return false;
        }

        return InnerBuilderUtils.getTopLevelClass(project, file, editor) != null && isApplicable(file, editor);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    private static boolean isApplicable(PsiFile file, Editor editor) {
        List<PsiFieldMember> targetElements = collectFields(file, editor);
        return targetElements != null && !targetElements.isEmpty();
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);
        Document currentDocument = psiDocumentManager.getDocument(file);
        if (currentDocument == null) {
            return;
        }

        psiDocumentManager.commitDocument(currentDocument);

        if (!CodeInsightUtilBase.prepareEditorForWrite(editor)) {
            return;
        }

        if (!FileDocumentManager.getInstance().requestWriting(editor.getDocument(), project)) {
            return;
        }

        List<PsiFieldMember> existingFields = collectFields(file, editor);
        if (existingFields != null) {
            List<PsiFieldMember> selectedFields = selectFieldsAndOptions(existingFields, project);

            if (selectedFields == null || selectedFields.isEmpty()) {
                return;
            }

            InnerBuilderGenerator.generate(project, editor, file, selectedFields);
        }
    }

}
