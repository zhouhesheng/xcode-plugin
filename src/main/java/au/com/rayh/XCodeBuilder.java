package au.com.rayh;
import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import java.io.ByteArrayOutputStream;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.apache.commons.lang.StringUtils;

/**
 * @author Ray Hilton
 */
public class XCodeBuilder extends Builder {
    private Boolean buildIpa;
    private Boolean cleanBeforeBuild;
    private Boolean updateBuildNumber;
    private String configuration = "Release";
    private String target;
    private String sdk;
    private String xcodeProjectPath;
    private String xcodeProjectFile;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public XCodeBuilder(Boolean buildIpa, Boolean cleanBeforeBuild, Boolean updateBuildNumber, String configuration, String target, String sdk,
            String xcodeProjectPath, String xcodeProjectFile) {
        this.buildIpa = buildIpa;
        this.sdk = sdk;
        this.target = target;
        this.cleanBeforeBuild = cleanBeforeBuild;
        this.updateBuildNumber = updateBuildNumber;
        this.configuration = configuration;
        this.xcodeProjectPath = xcodeProjectPath;
        this.xcodeProjectFile = xcodeProjectFile;
    }

    public String getSdk() {
        return sdk;
    }

    public String getTarget() {
        return target;
    }

    public String getConfiguration() {
        return configuration;
    }

    public Boolean getBuildIpa() {
        return buildIpa;
    }

    public Boolean getCleanBeforeBuild() {
        return cleanBeforeBuild;
    }

    public Boolean getUpdateBuildNumber() {
        return updateBuildNumber;
    }

    public String getXcodeProjectPath() {
        return xcodeProjectPath;
    }

    public String getXcodeProjectFile() {
        return xcodeProjectFile;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        EnvVars envs = build.getEnvironment(listener);
        FilePath projectRoot = build.getProject().getWorkspace();

        // check that the configured tools exist
        if(!new FilePath(projectRoot.getChannel(), getDescriptor().xcodebuildPath()).exists()) {
            listener.fatalError("Cannot find xcodebuild with the configured path {0}", getDescriptor().xcodebuildPath());
        }
        if(!new FilePath(projectRoot.getChannel(), getDescriptor().agvtoolPath()).exists()) {
            listener.fatalError("Cannot find agvtool with the configured path {0}", getDescriptor().agvtoolPath());
        }

        // Set the working directory
        if(!StringUtils.isEmpty(xcodeProjectPath)) {
            projectRoot = projectRoot.child(xcodeProjectPath);
        }
        listener.getLogger().println("Working directory is " + projectRoot);

        // XCode Version
        int returnCode = launcher.launch().envs(envs).cmds(getDescriptor().xcodebuildPath(), "-version").stdout(listener).pwd(projectRoot).join();
        if(returnCode>0) return false;

        // Unlock keychain
//        if(!StringUtils.isEmpty(keychainPassword)) {
//            launcher.launch().envs(envs).cmds("security", "unlock-keychain", "-p", keychainPassword);
//        }

        // Set build number
        if(updateBuildNumber) {
            listener.getLogger().println("Updating version number");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            returnCode = launcher.launch().envs(envs).cmds("agvtool", "mvers", "-terse1").stdout(output).pwd(projectRoot).join();
            if(returnCode>0) return false;
            String marketingVersionNumber = output.toString().trim();
            String newVersion = marketingVersionNumber + "." + build.getNumber();
            listener.getLogger().println("CFBundlerShortVersionString is " + marketingVersionNumber + " so new CFBundleVersion will be " + newVersion);

            returnCode = launcher.launch().envs(envs).cmds(getDescriptor().agvtoolPath(), "new-version", "-all", newVersion ).stdout(listener).pwd(projectRoot).join();
            if(returnCode>0) return false;
        }


        // Build
        StringBuilder xcodeReport = new StringBuilder("Going to invoke xcodebuild: ");
        XCodeBuildOutputParser reportGenerator = new XCodeBuildOutputParser(projectRoot, listener);
        List<String> commandLine = Lists.newArrayList(getDescriptor().xcodebuildPath());
        if(StringUtils.isEmpty(target)) {
            commandLine.add("-alltargets");
            xcodeReport.append("target: ALL");
        } else {
            commandLine.add("-target");
            commandLine.add(target);
            xcodeReport.append("target: ").append(target);
        }
        
        if(!StringUtils.isEmpty(sdk)) {
            commandLine.add("-sdk");
            commandLine.add(sdk);
            xcodeReport.append(", sdk: ").append(sdk);
        } else {
            xcodeReport.append(", sdk: DEFAULT");
        }

        if(!StringUtils.isEmpty(xcodeProjectFile)) {
            commandLine.add("-project");
            commandLine.add(xcodeProjectFile);
            xcodeReport.append(", project: ").append(xcodeProjectFile);
        } else {
            xcodeReport.append(", project: DEFAULT");
        }

        commandLine.add("-configuration");
        commandLine.add(configuration);
        xcodeReport.append(", configuration: ").append(configuration);

        if (cleanBeforeBuild) {
            commandLine.add("clean");
            xcodeReport.append(", clean: YES");
        } else {
            xcodeReport.append(", clean: NO");
        }
        commandLine.add("build");
        
        listener.getLogger().println(xcodeReport.toString());
        returnCode = launcher.launch().envs(envs).cmds(commandLine).stdout(reportGenerator.getOutputStream()).pwd(projectRoot).join();
        if(reportGenerator.getExitCode()!=0) return false;
        if(returnCode>0) return false;


        // Package IPA
        if(buildIpa) {
            listener.getLogger().println("Packaging IPA");
            FilePath buildDir = projectRoot.child("build").child(configuration + "-iphoneos");
            List<FilePath> apps = buildDir.list(new AppFileFilter());

            for(FilePath app : apps) {
                FilePath ipaLocation = buildDir.child(app.getBaseName() + ".ipa");
                ipaLocation.delete();

                FilePath payload = buildDir.child("Payload");
                payload.deleteRecursive();
                payload.mkdirs();

                listener.getLogger().println("Packaging " + app.getBaseName() + ".app => " + app.getBaseName() + ".ipa");

                app.copyRecursiveTo(payload.child(app.getName()));
                payload.zip(ipaLocation.write());

                payload.deleteRecursive();
            }
        }

        return true;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        private String xcodebuildPath = "/usr/bin/xcodebuild";
        private String agvtoolPath = "/usr/bin/agvtool";

        public FormValidation doCheckConfiguration(@QueryParameter String value) throws IOException, ServletException {
            if (StringUtils.isEmpty(value)) {
                return FormValidation.error("Please specify a configuration");
            } else {
                // TODO: scan project file for specified configuration
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckXcodebuildPath(@QueryParameter String value) throws IOException, ServletException {
            if (StringUtils.isEmpty(value)) {
                return FormValidation.error("Please specify the path to the xcodebuild executable (usually /usr/bin/xcodebuild)");
            } else {
                // TODO: check that the file exists
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckAgvtoolPath(@QueryParameter String value) throws IOException, ServletException {
            if(StringUtils.isEmpty(value))
                return FormValidation.error("Please specify the path to the agvtool executable (usually /usr/bin/agvtool)");
            else {
                // TODO: check that the file exists
            }
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // indicates that this builder can be used with all kinds of project types
            return true;
        }

        public String getDisplayName() {
            return "XCode";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
//            updateBuildNumber = formData.getBoolean("updateBuildNumber");
//            buildIpa = formData.getBoolean("buildIpa");
//            cleanBeforeBuild = formData.getBoolean("cleanBeforeBuild");
//            configuration = formData.getString("configuration");
            xcodebuildPath = formData.getString("xcodebuildPath");
            agvtoolPath = formData.getString("agvtoolPath");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req, formData);
        }

        public String agvtoolPath() {
            return agvtoolPath;
        }

        public String xcodebuildPath() {
            return xcodebuildPath;
        }

    }
}

