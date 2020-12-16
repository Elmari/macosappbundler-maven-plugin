/*
 * macOS app bundler Maven plugin
 * Copyright 2019 Christian Seifert
 * Copyright 2020 Håvard Bakke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.perdian.maven.plugins.macosappbundler.mojo.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import de.perdian.maven.plugins.macosappbundler.mojo.model.NativeBinaryType;
import de.perdian.maven.plugins.macosappbundler.mojo.model.PlistConfiguration;

public class AppGenerator {

    private PlistConfiguration plistConfiguration = null;
    private NativeBinaryType nativeBinaryType = NativeBinaryType.UNIVERSAL;
    private boolean includeJdk = false;
    private String jdkLocation = null;
    private Log log = null;

    public AppGenerator(PlistConfiguration plistConfiguration, Log log) {
        this.setPlistConfiguration(plistConfiguration);
        this.setLog(log);
    }

    public void generateApp(MavenProject project, File appDirectory) throws MojoExecutionException {
        this.copyApplicationDependencies(project, new File(appDirectory, "Contents/Java"));
        this.copyJdk(new File(appDirectory, "Contents/Java/jdk"));
        this.copyNativeExecutable(new File(appDirectory, "Contents/MacOS"));
        this.generatePlist(project, new File(appDirectory, "Contents/"));
    }

    private void copyApplicationDependencies(MavenProject project, File appJavaDirectory) throws MojoExecutionException {
        this.getLog().info("Copy application dependencies to: " + appJavaDirectory.getAbsolutePath());
        try {
            if (StringUtils.isNotEmpty(this.getPlistConfiguration().JVMMainClassName)) {
                this.copyClasspathApplicationDependencies(project, new File(appJavaDirectory, "classpath"));
            } else if (StringUtils.isNotEmpty(this.getPlistConfiguration().JVMMainModuleName)) {
                this.copyModuleApplicationDependencies(project, new File(appJavaDirectory, "modules"));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy dependencies", e);
        }
    }

    private void copyClasspathApplicationDependencies(MavenProject project, File classpathDirectory) throws IOException {
        ArtifactRepositoryLayout repositoryLayout = new DefaultRepositoryLayout();
        this.copyClasspathApplicationDependencyArtifact(project.getArtifact(), classpathDirectory, repositoryLayout);
        for (Artifact artifact : project.getArtifacts()) {
            this.copyClasspathApplicationDependencyArtifact(artifact, classpathDirectory, repositoryLayout);
        }
    }

    private void copyClasspathApplicationDependencyArtifact(Artifact artifact, File targetDirectory, ArtifactRepositoryLayout repositoryLayout) throws IOException {
        File targetFile = new File(targetDirectory, repositoryLayout.pathOf(artifact));
        if (!targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }
        FileUtils.copyFile(artifact.getFile(), targetFile);
    }

    private void copyModuleApplicationDependencies(MavenProject project, File modulesDirectory) throws IOException {
        this.copyModuleApplicationDependencyArtifact(project.getArtifact(), modulesDirectory);
        for (Artifact artifact : project.getArtifacts()) {
            this.copyModuleApplicationDependencyArtifact(artifact, modulesDirectory);
        }
    }

    private void copyModuleApplicationDependencyArtifact(Artifact artifact, File modulesDirectory) throws IOException {
        StringBuilder targetFileName = new StringBuilder();
        targetFileName.append(artifact.getArtifactId());
        targetFileName.append("-").append(artifact.getVersion());
        targetFileName.append(".").append(FilenameUtils.getExtension(artifact.getFile().getName()));
        File targetFile = new File(modulesDirectory, targetFileName.toString());
        if (!targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }
        FileUtils.copyFile(artifact.getFile(), targetFile);
    }

    private void copyNativeExecutable(File targetDirectory) throws MojoExecutionException {
        try {
            URL nativeBinarySource = this.resolveNativeExecutable();
            String targetFileName = StringUtils.defaultIfEmpty(this.getPlistConfiguration().CFBundleExecutable, "JavaLauncher");
            File targetFile = new File(targetDirectory, targetFileName);
            this.getLog().info("Copy native executable for binary type " + this.getNativeBinaryType() + " to: " + targetFile.getAbsolutePath());
            try (InputStream nativeExecutableStream = nativeBinarySource.openStream()) {
                FileUtils.copyInputStreamToFile(nativeExecutableStream, targetFile);
            }
            targetFile.setExecutable(true, false);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy native executable", e);
        }
    }

    private URL resolveNativeExecutable() throws MojoExecutionException {
        URL nativeBinarySource = this.getClass().getClassLoader().getResource(this.getNativeBinaryType().getFilename());
        if (nativeBinarySource == null) {
            throw new MojoExecutionException("No native executable packaged in plugin for native binary type " + this.getNativeBinaryType().name() + " found at location: " + this.getNativeBinaryType().getFilename());
        } else {
            return nativeBinarySource;
        }
    }

    private void generatePlist(MavenProject project, File contentsDirectory) throws MojoExecutionException {
        Map<String, String> additionalProperties = new LinkedHashMap<>();
        String iconFileName = this.copyIcon(project, contentsDirectory);
        if (StringUtils.isNotEmpty(iconFileName)) {
            additionalProperties.put("CFBundleIconFile", iconFileName);
        }
        try {
            File plistFile = new File(contentsDirectory, "Info.plist");
            this.getLog().info("Generating Info.plist");
            FileUtils.write(plistFile, this.getPlistConfiguration().toXmlString(additionalProperties), "UTF-8");
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot generate Info.plist file", e);
        }
    }

    private String copyIcon(MavenProject project, File contentsDirectory) throws MojoExecutionException {
        String iconFileValue = this.getPlistConfiguration().CFBundleIconFile;
        if (StringUtils.isNotEmpty(iconFileValue)) {
            File iconFile = new File(project.getBasedir(), iconFileValue);
            if (!iconFile.exists()) {
                throw new MojoExecutionException("Cannot find declared icon file " + iconFile.getName() + " at: " + iconFile.getAbsolutePath());
            } else {
                File resourcesDirectory = new File(contentsDirectory, "Resources");
                File targetFile = new File(resourcesDirectory, iconFile.getName());
                if (!targetFile.getParentFile().exists()) {
                    targetFile.getParentFile().mkdirs();
                }
                try {
                    FileUtils.copyFile(iconFile, targetFile);
                    return targetFile.getName();
                } catch (IOException e) {
                    throw new MojoExecutionException("Cannot copy icon file to: " + targetFile.getAbsolutePath(), e);
                }
            }
        } else {
            return null;
        }
    }

    private void copyJdk(File targetDirectory) throws MojoExecutionException {
        if (this.isIncludeJdk()) {
            if (StringUtils.isEmpty(this.getJdkLocation())) {
                this.getLog().info("Copy JDK from system default directory at: " + System.getProperty("java.home"));
                this.copyJdkFromDirectory(targetDirectory, new File(System.getProperty("java.home")));
            } else {
                File jdkDirectory = new File(this.getJdkLocation());
                if (!jdkDirectory.exists()) {
                    throw new MojoExecutionException("Specified JDK directory doesn't exist at: " + jdkDirectory.getAbsolutePath());
                } else {
                    this.getLog().info("Copy JDK from explicit directory at: " + jdkDirectory.getAbsolutePath());
                    this.copyJdkFromDirectory(targetDirectory, jdkDirectory);
                }
            }
        }
    }

    private void copyJdkFromDirectory(File targetDirectory, File sourceDirectory) throws MojoExecutionException {
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs();
        }
        try {
            FileUtils.copyDirectory(sourceDirectory, targetDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy JDK from directory at: " + sourceDirectory.getAbsolutePath(), e);
        }
    }

    private PlistConfiguration getPlistConfiguration() {
        return this.plistConfiguration;
    }
    private void setPlistConfiguration(PlistConfiguration plistConfiguration) {
        this.plistConfiguration = plistConfiguration;
    }

    public boolean isIncludeJdk() {
        return this.includeJdk;
    }
    public void setIncludeJdk(boolean includeJdk) {
        this.includeJdk = includeJdk;
    }

    public NativeBinaryType getNativeBinaryType() {
        return this.nativeBinaryType;
    }
    public void setNativeBinaryType(NativeBinaryType nativeBinaryType) {
        this.nativeBinaryType = nativeBinaryType;
    }

    public String getJdkLocation() {
        return this.jdkLocation;
    }
    public void setJdkLocation(String jdkLocation) {
        this.jdkLocation = jdkLocation;
    }

    private Log getLog() {
        return this.log;
    }
    private void setLog(Log log) {
        this.log = log;
    }

}
