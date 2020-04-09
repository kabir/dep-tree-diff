package org.wildfly.deptreediff.reporter.github;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.wildfly.deptreediff.core.DepTreeDiffReporter;
import org.wildfly.deptreediff.core.VersionChange;

/**
 * @author <a href="mailto:kabir.khan@jboss.com">Kabir Khan</a>
 */
public class GitHubDiffReporter implements DepTreeDiffReporter {

    private static final String COMMENT_HEADER = "**Dependency Tree Analyzer Output:**";
    private static final boolean TRACE = Boolean.getBoolean("deptree.tool.reporter.github.trace");

    private static final String TOKEN = "deptree.tool.reporter.github.token";
    private static final String DEPS_OK = "deptree.tool.reporter.github.deps-ok.label";
    private static final String DEPS_CHANGED = "deptree.tool.reporter.github.deps-changed.label";
    // Format: <org>/<repo>
    private static final String REPOSITORY = "deptree.tool.reporter.github.repo";
    private static final String PULL_REQUEST = "deptree.tool.reporter.github.pr";
    private static final String CHANGE_MENTIONS = "deptree.tool.reporter.github.change.mentions";

    List<String> newDependencies = new ArrayList<>();
    List<String> removedDependencies = new ArrayList<>();
    List<VersionChange> majorVersionChanges = new ArrayList<>();

    @Override
    public void addNewDependency(String gav) {
        newDependencies.add(gav);
        if (TRACE) {
            System.out.println("Added " + gav);
        }
    }

    @Override
    public void addRemovedDependency(String gav) {
        removedDependencies.add(gav);
        if (TRACE) {
            System.out.println("Removed " + gav);
        }
    }

    @Override
    public void addMajorVersionUpgrade(VersionChange change) {
        majorVersionChanges.add(change);
        if (TRACE) {
            System.out.println("Major Version " + change.getOriginalGavString() + " -> " + change.getNewVersion());
        }

    }

    @Override
    public void done() throws Exception {

        if (TRACE) {
            System.out.println("Finishing report. Interaction with GitHub starting");
        }

        String token = System.getProperty(TOKEN);
        if (token == null) {
            throw new IllegalStateException("No token was provided. Supply it in -D" + TOKEN);
        }
        System.out.println("Token: " + token);

        String depsOkLabel = System.getProperty(DEPS_OK);
        if (depsOkLabel == null) {
            throw new IllegalStateException("No label for dependencies ok was provided. Supply it in -D" + DEPS_OK);
        }

        String depsChangedLabel = System.getProperty(DEPS_CHANGED);
        if (depsChangedLabel == null) {
            throw new IllegalStateException("No label for dependencies changed was provided. Supply it in -D" + DEPS_CHANGED);
        }

        String orgAndRepo = System.getProperty(REPOSITORY);
        if (orgAndRepo == null) {
            throw new IllegalStateException("No repository was provided in <org>/<repo> format. Supply it in -D" + REPOSITORY);
        }
        if (!orgAndRepo.contains("/")) {
            throw new IllegalStateException("A repository was provided but is not in <org>/<repo> format. Supply it in -D" + REPOSITORY + "=<org>/<repo>");
        }
        if (orgAndRepo.startsWith("/")) {
            throw new IllegalStateException("There should not be a leading slash in the value for -D" + REPOSITORY + "=" + orgAndRepo);
        }
        if (orgAndRepo.endsWith("/")) {
            throw new IllegalStateException("There should not be a trailing slash in the value for -D" + REPOSITORY + "=" + orgAndRepo);
        }

        String pr = System.getProperty(PULL_REQUEST);
        if (pr == null) {
            throw new IllegalStateException("No pull request number was provided. Supply it as -D" + PULL_REQUEST);
        }
        GitHubApi api = new GitHubApi(token, depsOkLabel, depsChangedLabel, orgAndRepo, Integer.parseInt(pr));
        api.createDependencyReport();

        if (TRACE) {
            System.out.println("Finished report. All done");
        }
    }

    String formatPrComment() {
        StringBuilder sb = new StringBuilder(COMMENT_HEADER);
        sb.append("\r\n\r\n");
        if (newDependencies.size() > 0) {
            sb.append("New Dependencies:\r\n");
            for (String newDep : newDependencies) {
                sb.append("* " + newDep + "\r\n");
            }
            sb.append("\r\n");
        }
        if (removedDependencies.size() > 0) {
            sb.append("Removed Dependencies:\r\n");
            for (String removedDep : removedDependencies) {
                sb.append("* " + removedDep + "\r\n");
            }
            sb.append("\r\n");
        }
        if (majorVersionChanges.size() > 0) {
            sb.append("Major Version Changes:\r\n");
            for (VersionChange change : majorVersionChanges) {
                sb.append("* " + change.getOriginalGavString() + " -> " + change.getNewVersion() + "\r\n");
            }
            sb.append("\r\n");
        }

        String mentions = System.getProperty(CHANGE_MENTIONS);
        if (mentions != null && mentions.trim().length() > 0) {
            String[] parts = mentions.split(",");
            sb.append("\r\n");
            sb.append("CC");
            for (String mention : parts) {
                sb.append(" ");
                sb.append(mention);
            }
            sb.append("\r\n");
        }

        return sb.toString();
    }

    private class GitHubApi {
        private final String token;
        private final String depsOkLabel;
        private final String depsChangedLabel;
        private final String orgAndRepo;
        private final int pr;

        public GitHubApi(String token, String depsOkLabel, String depsChangedLabel, String orgAndRepo, int pr) {
            this.token = token;
            this.depsOkLabel = depsOkLabel;
            this.depsChangedLabel = depsChangedLabel;
            this.orgAndRepo = orgAndRepo;
            this.pr = pr;

            if (TRACE) {
                System.out.println("Creating GH reporter:");
                System.out.println("- Have API token " + (token != null ? true : false));
                System.out.println("- Deps OK Label Name: " + depsOkLabel);
                System.out.println("- Deps Changed Label Name: " + depsChangedLabel);
                System.out.println("- Org/Repo: " + orgAndRepo);
                System.out.println("- PR #: " + pr);
            }
        }

        public void createDependencyReport() throws IOException {
            // List all the labels
            checkConfiguredLabels();

            Set<String> prLabels = getLabelNamesForPr();

            if (TRACE) {
                System.out.println("Existing labels on PR: " + prLabels);
            }

            if (newDependencies.size() > 0 || removedDependencies.size() > 0 || majorVersionChanges.size() > 0) {
                if (TRACE) {
                    System.out.println("Dependency tree changed");
                }
                // Add depsChangedLabel and remove depsOkLabel if it exists
                addLabelIfMissing(prLabels, depsChangedLabel);
                deleteLabelIfExists(prLabels, depsOkLabel);
                addOrUpdatePullRequestComment();
            } else {
                if (TRACE) {
                    System.out.println("Dependency tree is the same");
                }
                // Add depsOkLabel and remove depsChangedLabel if it exists
                addLabelIfMissing(prLabels, depsOkLabel);
                deleteLabelIfExists(prLabels, depsChangedLabel);
                deletePullRequestComment();
            }
        }

        private void deletePullRequestComment() throws IOException {
            String existingCommentId = findPullRequestCommentId();
            if (existingCommentId != null) {
                String url = "https://api.github.com/repos/" + orgAndRepo + "/issues/comments/" + existingCommentId;
                HttpURLConnection connection =
                        (HttpURLConnection)new URL(url).openConnection();
                try {
                    connection.setRequestMethod("DELETE");
                    connection.setRequestProperty("Authorization", "token " + token);

                    int responseCode = connection.getResponseCode();
                    if (responseCode / 100 != 2) {
                        throw new IllegalStateException("Bad response code for " + url + ": " + responseCode);
                    }

                } finally {
                    connection.disconnect();
                }
            }
        }

        private void addOrUpdatePullRequestComment() throws IOException {
            String existingCommentId = findPullRequestCommentId();

            ModelNode commentBody = new ModelNode();
            commentBody.get("body").set(formatPrComment());

            if (existingCommentId == null) {
                // POST a new one
                if (TRACE) {
                    System.out.println("Adding a new comment");
                }
                String url = "https://api.github.com/repos/" + orgAndRepo + "/issues/" + pr + "/comments";
                doPostPutOrPatchModelNode("POST", url, commentBody);
            } else {
                if (TRACE) {
                    System.out.println("Updating the existing comment");
                }
                // PATCH the existing one
                String url = "https://api.github.com/repos/" + orgAndRepo + "/issues/comments/" + existingCommentId;
                doPostPutOrPatchModelNode("PATCH", url, commentBody);
            }
        }

        private void addLabelIfMissing(Set<String> prLabels, String label) throws IOException {
            if (TRACE) {
                System.out.println("Checking if " + label + " exists in " + prLabels + ". Want to add it if not");
            }
            if (!prLabels.contains(label)) {
                addLabel(label);
            }
        }

        private void deleteLabelIfExists(Set<String> prLabels, String label) throws IOException {
            if (TRACE) {
                System.out.println("Checking if " + label + " exists in " + prLabels + ". Want to remove it if not");
            }
            if (prLabels.contains(label)) {
                deleteLabel(label);
            }
        }

        private void checkConfiguredLabels() throws IOException  {
            if (TRACE) {
                System.out.println("Checking configured labels");
            }

            ModelNode node = doGetAsModelNode("https://api.github.com/repos/" + orgAndRepo + "/labels");

            HashSet<String> labels = new HashSet<>();
            labels.add(depsChangedLabel);
            labels.add(depsOkLabel);

            if (node.getType() != ModelType.LIST) {
                throw new IllegalStateException("Not a list: " + node);
            }
            for (ModelNode labelNode : node.asList()) {
                labels.remove(labelNode.get("name").asString());
                if (labels.size() == 0) {
                    break;
                }
            }
            if (labels.size() > 0) {
                throw new IllegalStateException("Could not find the following labels in the '" + orgAndRepo + "' repository: " + labels);
            }

            if (TRACE) {
                System.out.println("Checked configured labels");
            }
        }

        private Set<String> getLabelNamesForPr() throws IOException {
            ModelNode node = doGetAsModelNode("https://api.github.com/repos/" + orgAndRepo + "/issues/" + pr + "/labels");
            if (node.getType() != ModelType.LIST) {
                throw new IllegalStateException("Not a list: " + node);
            }

            HashSet<String> labels = new HashSet<>();
            for (ModelNode labelNode : node.asList()) {
                labels.add(labelNode.get("name").asString());
            }
            return Collections.unmodifiableSet(labels);
        }

        private void addLabel(String label) throws IOException {
            ModelNode labelsList = new ModelNode();
            labelsList.setEmptyList();
            labelsList.add(label);

            ModelNode labelsNode = new ModelNode();
            labelsNode.get("labels").set(labelsList);
            doPostPutOrPatchModelNode("POST", "https://api.github.com/repos/" + orgAndRepo + "/issues/" + pr + "/labels", labelsNode);
        }

        private void deleteLabel(String label) throws IOException {
            String url = "https://api.github.com/repos/" + orgAndRepo + "/issues/" + pr + "/labels/" + label;
            HttpURLConnection connection =
                    (HttpURLConnection)new URL(url).openConnection();
            try {
                connection.setRequestMethod("DELETE");
                connection.setRequestProperty("Authorization", "token " + token);

                int responseCode = connection.getResponseCode();
                if (responseCode / 100 != 2) {
                    throw new IllegalStateException("Bad response code for " + url + ": " + responseCode);
                }

            } finally {
                connection.disconnect();
            }
        }

        private String findPullRequestCommentId() throws IOException {
            if (TRACE) {
                System.out.println("Looking for pull request comment");
            }
            String myUserId = getMyUserId();

            ModelNode node = doGetAsModelNode("https://api.github.com/repos/" + orgAndRepo + "/issues/" + pr + "/comments");
            if (node.getType() != ModelType.LIST) {
                throw new IllegalStateException("Not a list: " + node);
            }


            for (ModelNode comment : node.asList()) {
                String userId = comment.get("user", "id").asString();
                if (userId.equals(myUserId)) {
                    String body = comment.get("body").asString();
                    if (body.startsWith(COMMENT_HEADER)) {
                        if (TRACE) {
                            System.out.println("Found comment for user matching the string: " + comment);
                        }
                        return comment.get("id").asString();
                    }
                }
            }
            if (TRACE) {
                System.out.println("Could not find an existing comment");
            }
            return null;
        }

        private String getMyUserId() throws IOException {
            ModelNode userNode = doGetAsModelNode("https://api.github.com/user");
            return userNode.get("id").asString();
        }

        private ModelNode doGetAsModelNode(String url) throws IOException {
            if (TRACE) {
                System.out.println("Doing GET " + url);
            }
            HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Authorization", "token " + token);

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new IllegalStateException("Bad response code for GET " + url + ": " + responseCode);
                }

                try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                    ModelNode node =  ModelNode.fromJSONStream(in);
                    if (TRACE) {
                        System.out.println("GET returned: " + node);
                    }
                    return node;
                }
            } finally {
                connection.disconnect();
            }
        }

        private void doPostPutOrPatchModelNode(String method, String url, ModelNode body) throws IOException {
            if (!method.equals("POST") && !method.equals("PUT") && !method.equals("PATCH")) {
                throw new IllegalStateException("Bad method: " + method);
            }

            if (TRACE) {
                System.out.println(method + " " + url + " with body: " + body);
            }



            HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
            try {
                if (method.equals("PATCH")) {
                    //Work around Java not supporting PATCH, which is needed in the API
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                } else {
                    connection.setRequestMethod(method);
                }
                connection.setRequestProperty("Authorization", "token " + token);
                connection.setDoOutput(true);

                try (PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(connection.getOutputStream())))) {
                    body.writeJSONString(writer, true);
                    writer.flush();
                }
                int responseCode = connection.getResponseCode();
                if (responseCode / 100 != 2) {
                    throw new IllegalStateException("Bad response code for " + method + " " + url + ": " + responseCode);
                }

            } finally {
                connection.disconnect();
            }
        }
    }
}
