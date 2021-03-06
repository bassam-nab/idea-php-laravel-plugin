package de.espend.idea.laravel.view;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.jetbrains.php.blade.BladeFileType;
import com.jetbrains.php.lang.PhpFileType;
import de.espend.idea.laravel.LaravelSettings;
import de.espend.idea.laravel.util.VfsExUtil;
import de.espend.idea.laravel.view.dict.JsonTemplatePaths;
import de.espend.idea.laravel.view.dict.TemplatePath;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class ViewCollector {

    @NotNull
    public static Collection<TemplatePath> getPaths(@NotNull Project project) {
        return getPaths(project, true, true);
    }

    @NotNull
    public static Collection<TemplatePath> getPaths(@NotNull Project project, boolean includeSettings, boolean includeJson) {

        // "resources/views" -> laravel 4
        // "app/views" -> laravel 5 (deprecated)
        // "resources/templates" -> laravel 5

        String[] defaultDirs = new String[] {"resources/views", "app/views", "resources/templates"};
        Collection<TemplatePath> templatePaths = new ArrayList<>();

        for(String path: new HashSet<>(Arrays.asList(defaultDirs))) {
            templatePaths.add(new TemplatePath(path, false));
        }

        // ide-blade.json files
        if(includeJson) {
            collectIdeJsonBladePaths(project, templatePaths);
        }

        if(includeSettings) {
            List<TemplatePath> paths = LaravelSettings.getInstance(project).templatePaths;
            if(paths != null) {
                for(TemplatePath templatePath : paths) {
                    templatePaths.add(templatePath.clone());
                }
            }
        }

        return templatePaths;
    }

    private static void collectIdeJsonBladePaths(@NotNull Project project, @NotNull Collection<TemplatePath> templatePaths) {
        for (final PsiFile psiFile : FilenameIndex.getFilesByName(project, "ide-blade.json", GlobalSearchScope.allScope(project))) {
            Collection<TemplatePath> cachedValue = CachedValuesManager.getCachedValue(psiFile, new MyJsonCachedValueProvider(psiFile));
            if(cachedValue != null) {
                templatePaths.addAll(cachedValue);
            }
        }
    }

    public static void visitFile(@NotNull Project project, @NotNull final ViewVisitor visitor) {
        for(TemplatePath templatePath : getPaths(project)) {
            visitTemplatePath(project, templatePath, visitor);
        }
    }

    public static void visitTemplatePath(@NotNull Project project, final @NotNull TemplatePath templatePath, @NotNull final ViewVisitor visitor) {
        final VirtualFile templateDir = VfsUtil.findRelativeFile(templatePath.getPath(), project.getBaseDir());
        if(templateDir == null) {
            return;
        }

        VfsUtil.visitChildrenRecursively(templateDir, new VirtualFileVisitor() {
            @Override
            public boolean visitFile(@NotNull VirtualFile virtualFile) {

                if(virtualFile.isDirectory() || !isTemplateFile(virtualFile)) {
                    return true;
                }

                String filename = VfsUtil.getRelativePath(virtualFile, templateDir, '.');
                if(filename == null) {
                    return true;
                }

                if(filename.endsWith(".php")) {
                    filename = filename.substring(0, filename.length() - 4);
                }

                if(filename.endsWith(".blade")) {
                    filename = filename.substring(0, filename.length() - 6);
                }

                if(filename.endsWith(".html.twig")) {
                    filename = filename.substring(0, filename.length() - ".html.twig".length());
                }

                String namespace = templatePath.getNamespace();
                if(namespace != null && StringUtils.isNotBlank(namespace)) {
                    visitor.visit(virtualFile, namespace + "::" + filename);
                } else {
                    visitor.visit(virtualFile, filename);
                }

                return true;
            }

            private boolean isTemplateFile(VirtualFile virtualFile) {

                if(virtualFile.getFileType() == BladeFileType.INSTANCE || virtualFile.getFileType() == PhpFileType.INSTANCE) {
                    return true;
                }

                String extension = virtualFile.getExtension();
                if(extension != null && (extension.equalsIgnoreCase("php") || extension.equalsIgnoreCase("twig"))) {
                    return true;
                }

                return false;
            }

        });

    }

    public interface ViewVisitor {
        void visit(@NotNull VirtualFile virtualFile, String name);
    }

    private static class MyJsonCachedValueProvider implements CachedValueProvider<Collection<TemplatePath>> {
        private final PsiFile psiFile;

        public MyJsonCachedValueProvider(PsiFile psiFile) {
            this.psiFile = psiFile;
        }

        @Nullable
        @Override
        public Result<Collection<TemplatePath>> compute() {

            Collection<TemplatePath> twigPaths = new ArrayList<>();

            String text = psiFile.getText();
            JsonTemplatePaths configJson = null;
            try {
                configJson = new Gson().fromJson(text, JsonTemplatePaths.class);
            } catch (JsonSyntaxException | JsonIOException | IllegalStateException ignored) {
            }

            if(configJson == null) {
                return Result.create(twigPaths, psiFile, psiFile.getVirtualFile());
            }

            Collection<JsonTemplatePaths.Path> namespaces = configJson.getNamespaces();
            if(namespaces == null || namespaces.size() == 0) {
                return Result.create(twigPaths, psiFile, psiFile.getVirtualFile());
            }

            for(JsonTemplatePaths.Path jsonPath : namespaces) {
                String path = jsonPath.getPath();
                if(path == null) {
                    path = "";
                }

                path = StringUtils.stripStart(path.replace("\\", "/"), "/");
                PsiDirectory parent = psiFile.getParent();
                if(parent == null) {
                    continue;
                }

                // current directory check and subfolder
                VirtualFile twigRoot;
                if(path.length() > 0) {
                    twigRoot = VfsUtil.findRelativeFile(parent.getVirtualFile(), path.split("/"));
                } else {
                    twigRoot = psiFile.getParent().getVirtualFile();
                }

                if(twigRoot == null) {
                    continue;
                }

                String relativePath = VfsExUtil.getRelativeProjectPath(psiFile.getProject(), twigRoot);
                if(relativePath == null) {
                    continue;
                }

                String namespace = jsonPath.getNamespace();

                String namespacePath = StringUtils.stripStart(relativePath, "/");

                if(StringUtils.isNotBlank(namespace)) {
                    twigPaths.add(new TemplatePath(namespacePath, namespace, true));
                } else {
                    twigPaths.add(new TemplatePath(namespacePath, true));
                }
            }

            return Result.create(twigPaths, psiFile, psiFile.getVirtualFile());
        }
    }
}
