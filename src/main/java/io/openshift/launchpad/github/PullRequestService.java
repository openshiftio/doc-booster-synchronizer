package io.openshift.launchpad.github;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RemoteAddCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.yaml.snakeyaml.Yaml;

import io.openshift.booster.catalog.Booster;
import io.openshift.launchpad.github.model.Mapping;
import io.openshift.launchpad.github.model.PullRequest;

/**
 * Service for pull requests.
 */
@ApplicationScoped
public class PullRequestService {

    private static final String BRANCH_NAME = "documentation-update";
	private Map<String, String> map = (Map<String, String>) new Yaml().load(getClass().getResourceAsStream("/mapping.yml"));

    /**
     * Is this PR an update of the booster index.html file
     *
     * @param repoName the repo to check
     * @param pr       the PR number to check
     * @return the name of the mission to update or null if this is not a documentation update
     */
    public Mapping isDocumentationUpdated(String repoName, Integer pr) {
        try {
            GitHub gitHub = GitHub.connectAnonymously();
            GHRepository repository = gitHub.getRepository(repoName);

            PagedIterable<GHPullRequestFileDetail> files = repository.getPullRequest(pr).listFiles();
            for (GHPullRequestFileDetail file : files) {
                for (Map.Entry<String, String> fileName : map.entrySet()) {
                    if (fileName.getKey().equals(file.getFilename())) {
                        return new Mapping(fileName.getKey(), fileName.getValue());
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * Find the boosters that need updating and create forks if needed.
     *
     * @param boosterList list of all boosters
     * @param missionId the id of the mission to update.
     * @return the locations of the forks.
     */
    public List<File> fork(List<Booster> boosterList, String missionId) {
        List<File> result = new ArrayList<>();
        try {
            for (Booster booster : boosterList) {
                if (missionId.equals(booster.getMission().getId())) {
                    String githubRepo = booster.getGithubRepo();
                    GitHub gitHub = getGitHub();

                    GHRepository fork = gitHub.getRepository(githubRepo).fork();
                    result.add(checkout(fork).getRepository().getWorkTree());
                }
            }
        } catch (IOException | GitAPIException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    static GitHub getGitHub() throws IOException {
        return GitHub.connectUsingOAuth(System.getenv("GITHUB_TOKEN"));
    }

    /**
     * Create a PR for a change that was made in this repo.
     * 
     * @param repo with the change we want a PR for
     * @param pullRequest the pull request that initiated this change.
     */
    public void createPullRequest(File repo, PullRequest pullRequest) {
        try (Git gitRepo = Git.open(repo)) {
            createBranch(gitRepo);
            commit(gitRepo);
            push(gitRepo);
            GHPullRequest pr = createPR(gitRepo);
            linkPR(pr, pullRequest);
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Checkout the pull request with the proposed change
     * @param pullRequest the pull request to checkout
     * @return the location of the checked out repository
     * @throws IOException
     */
    public File checkout(PullRequest pullRequest) throws IOException {
    	GitHub gitHub = getGitHub();
    	GHRepository repo = gitHub.getRepository(pullRequest.getRepository().getFullName());
    	try (Git git = checkout(repo)) {
    		switchBranch(git, repo, pullRequest.getNumber());
    		return git.getRepository().getWorkTree();
    	} catch (GitAPIException | URISyntaxException e) {
    		throw new RuntimeException(e);
    	}
    }

    private void switchBranch(Git git, GHRepository repo, Integer prNumber) throws IOException, GitAPIException, URISyntaxException {
        GHCommitPointer head = repo.getPullRequest(prNumber).getHead();
        String url = head.getRepository().gitHttpTransportUrl();
        if (!url.equals(repo.getPullRequest(prNumber).getRepository().gitHttpTransportUrl())) {
        	git.branchCreate().setName(head.getRef()).call();
        	
        	RemoteAddCommand addCommand = git.remoteAdd();
            addCommand.setUri(new URIish(url));
            addCommand.setName("pr");
            addCommand.call();
            
        	git.pull().setRemote("pr").setRemoteBranchName(head.getRef()).call();
        } else {
        	git.checkout().setCreateBranch(true).setName(head.getRef()).call();
        }
    }

    private void linkPR(GHPullRequest pullRequest, PullRequest origPR) throws IOException {
        GHPullRequest origPRGH = getGitHub().getRepository(origPR.getRepository().getFullName()).getPullRequest(origPR.getNumber());
        origPRGH.comment("automatic PR created to update the booster " + pullRequest.getHtmlUrl());
    }

    private GHPullRequest createPR(Git gitRepo) throws IOException {
        String url = gitRepo.getRepository().getConfig().getString( "remote", "origin", "url" );
        String name = url.substring(url.lastIndexOf('/'), url.lastIndexOf('.'));

        GitHub hub = getGitHub();
        GHRepository repo = hub.getRepository(hub.getMyself().getLogin() + name);
        String head = hub.getMyself().getLogin() + ":" + BRANCH_NAME;
        return repo.getParent().createPullRequest("Doc update", head, "master", "*automatic created PR* triggerd by documentation update");
    }


    private void updateFork(Git git, GHRepository repo) throws GitAPIException, URISyntaxException, IOException {
        RemoteAddCommand addCommand = git.remoteAdd();
        addCommand.setUri(new URIish(repo.getParent().gitHttpTransportUrl()));
        addCommand.setName("upstream");
        addCommand.call();
        git.pull().setRemote("upstream").setRemoteBranchName("master").call();
    }

    private Git checkout(GHRepository gitHubRepository) throws IOException, GitAPIException, URISyntaxException {
        File path = Files.createTempDirectory("checkout").toFile();
        Git git = Git.cloneRepository().setDirectory(path)
                .setURI(gitHubRepository.gitHttpTransportUrl()).call();
        if (gitHubRepository.isFork()) {
        	updateFork(git, gitHubRepository);
        }
        return git;
    }

    private void createBranch(Git gitRepo) throws GitAPIException {
        gitRepo.branchDelete().setBranchNames(BRANCH_NAME).setForce(true).call();
        gitRepo.branchCreate().setName(BRANCH_NAME).call();
        gitRepo.checkout().setName(BRANCH_NAME).call();
    }

    private void commit(Git repo) throws GitAPIException {
        repo.add().addFilepattern(".").call();
        repo.commit().setMessage("automatically updated").call();
    }

    private void push(Git repo) throws GitAPIException {
        UsernamePasswordCredentialsProvider credentialsProvider =
                new UsernamePasswordCredentialsProvider(System.getenv("GITHUB_TOKEN"), "");
        repo.push().setCredentialsProvider(credentialsProvider).call();
    }

}
