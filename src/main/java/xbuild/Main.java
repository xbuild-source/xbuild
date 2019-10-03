package xbuild;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.archive.ArchiveFormats;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;

/**
 * xbuild
 * 
 * if arg is a url then it is interpreted as the remote git url
 * if arg is a branch then it is interpreted as the build branch
 * if arg is a number then it is interpreted as the build number
 * if arg is a commit then it is interpreted as the build commit
 * if arg is a file then it is interpreted as the deploy script
 */
@SpringBootApplication // https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle
public class Main implements ApplicationRunner {

  public static void main(String[] args) throws Exception {
    // args = new String[]{"asdf"};
    // args = new String[]{"git@github.com:rrizun/xbuild-java.git"};
    SpringApplication.run(Main.class, args);
  }

  static {
    ArchiveFormats.registerAll();
  }

  private final ApplicationContext context;

  public Main(ApplicationContext context) {
    this.context = context;
    // BuildProperties buildProperties = context.getBeanProvider(BuildProperties.class).getIfAvailable();
    // if (buildProperties != null) {
    //   log("xbuild", buildProperties.getVersion());
    //   // for (BuildProperties.Entry entry : buildProperties)
    //   //   log(entry.getKey(), entry.getValue());
    // }
}

  private File getGitDir(ApplicationArguments args) {
    List<String> values = args.getOptionValues("git-dir");
    if (values != null) {
      for (String value : values)
        return new File(value);
    }
    return null;
  }

  // git rev-list master --count --first-parent
  // https://stackoverflow.com/questions/14895123/auto-version-numbering-your-android-app-using-git-and-eclipse/20584169#20584169
  private BiMap<String, RevCommit> walkFirstParent(Repository repo, String branch) throws Exception {
    ObjectId objectId = repo.resolve(branch);
    BiMap<String, RevCommit> commitMap = HashBiMap.create();
    try (RevWalk walk = new RevWalk(repo)) {
      int count = -1;
      RevCommit head = walk.parseCommit(objectId);
      while (head != null) {
        ++count;
        commitMap.put(""+count, head);
        RevCommit[] parents = head.getParents();
        head = null;
        if (parents != null && parents.length > 0)
          head = walk.parseCommit(parents[0]);
      }
    }
    return reverse(commitMap);
  }

  private boolean verbose = true;

  private Git privateGit;
  private String privateBranch; // e.g., "master"
  private BiMap<String, RevCommit> privateCommitMap;
  private String number;
  private RevCommit commit;
  private final List<String> scripts = Lists.newArrayList();
  private Path workTree;

  // lazy
  private Git git() throws Exception {
    if (privateGit == null) {
      Repository repository = new FileRepositoryBuilder()
      // .setGitDir(getGitDir(args)) // --git-dir if supplied, no-op if null
      .readEnvironment() // scan environment GIT_* variables
      .findGitDir() // scan up the file system tree
      // .setBare()
      .build();
      privateGit = new Git(repository);
    }
    return privateGit;
  }

  private void setGit(String url) throws Exception {
    Path tempDirectory = Files.createTempDirectory("xbuild");
    log(tempDirectory);
    CloneCommand cloneCommand = Git.cloneRepository()
        .setBare(true)
        .setDirectory(tempDirectory.toFile())
        .setURI(url)
        .setProgressMonitor(new ProgressMonitor() {
          @Override
          public void start(int totalTasks) {
          }
          @Override
          public void beginTask(String title, int totalWork) {
            log(title, totalWork);
          }
          @Override
          public void update(int completed) {
          }
          @Override
          public void endTask() {
          }
          @Override
          public boolean isCancelled() {
            return false;
          }
        })
        ;
    privateGit = cloneCommand.call();
    setBranch(null);
  }

  // lazy
  private String branch() throws Exception {
    if (privateBranch == null)
      privateBranch = git().getRepository().getBranch();
    return privateBranch;
  }

  private void setBranch(String branch) throws Exception {
    privateBranch = branch;
    privateCommitMap = null;
    number = null;
    commit = null;
    scripts.clear();
    workTree = null;
  }

  // lazy
  private Path workTree() throws Exception {
    if (workTree == null) {
      workTree = Files.createTempDirectory("xbuild");

      log(workTree);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      log("archive", commit.abbreviate(7).name());
  
      git().archive()
        .setFormat("tar")
        .setOutputStream(baos)
        .setTree(commit)
        .call();

      untar(new ByteArrayInputStream(baos.toByteArray()), workTree);
    }
    return workTree;
  }

  // lazy
  private BiMap<String, RevCommit> commitMap() throws Exception {
    if (privateCommitMap == null) {
      privateCommitMap = walkFirstParent(git().getRepository(), branch());
    }
    return privateCommitMap;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {

    try {

      if (args.getOptionNames().contains("silent"))
        verbose = false;
      if (args.getOptionNames().contains("verbose"))
        verbose = true;

      if (args.getOptionNames().contains("version")) {
        String version = "version";
        BuildProperties buildProperties = context.getBeanProvider(BuildProperties.class).getIfAvailable();
        if (buildProperties != null) {
          version = buildProperties.getVersion();
          // for (BuildProperties.Entry entry : buildProperties)
          // log(entry.getKey(), entry.getValue());
        }
        System.out.println(version);
      } else {

        for (String arg : args.getNonOptionArgs()) {
          // is it a url?
          if (arg.contains(":")) {
            log("url", arg);
            setGit(arg);
          } else {
            // is it a branch?
            Ref ref = git().getRepository().findRef(arg);
            if (ref != null) {
              log("branch", arg);
              setBranch(arg);
            } else {
              // is it a number?
              if (commitMap().containsKey(arg)) {
                log("number", arg);
                number = arg;
              } else {
                // is it a commit?
                ObjectId objectId = git().getRepository().resolve(arg);
                if (objectId != null) {
                  log("commit[1]", arg);
                  commit = git().getRepository().parseCommit(objectId);
                  log("commit[2]", commit.name());
                } else {
                  // is it a script?
                  // File file = new File(workTree().toFile(), arg);
                  // boolean script = file.exists();
                  // if (script)
                  //   script = file.isFile();
                  // if (script) {
                    log("script", arg);
                    scripts.add(arg);
                  // } else {
                  //   throw new Exception(arg);
                  // }
                }
              }
            }
          }
        }

        // resolve number and commit

        if (number != null) // explicit number?
          commit = Objects.requireNonNull(commitMap().get(number), String.format("bad number: %s", number));
        else if (commit != null) // explicit commit?
          number = Objects.requireNonNull(commitMap().inverse().get(commit), String.format("bad branch/commit: %s/%s", branch(), commit.name()));
        else {
          // latest number and commit
          number = latest(commitMap().keySet());
          commit = Objects.requireNonNull(commitMap().get(number), String.format("bad number: %s", number));
        }

        // commitTime
        String commitTime = Instant.ofEpochSecond(commit.getCommitTime()).toString();

        Map<String, String> env = Maps.newTreeMap();

        String xbuild = String.format("%s-%s-%s", branch(), number, commit.abbreviate(7).name());

        env.put("XBUILD", xbuild); // "xbuild is running"
        env.put("XBUILD_BRANCH", branch());
        env.put("XBUILD_NUMBER", "" + number);
        env.put("XBUILD_COMMIT", commit.name());
        env.put("XBUILD_COMMITTIME", commitTime);
        env.put("XBUILD_DATETIME", commitTime); // ###LEGACY###

        for (Map.Entry<String, String> entry : env.entrySet())
          log(String.format("%s=%s", entry.getKey(), entry.getValue()));

        System.out.println(xbuild);

        if (scripts.size()>0) {
          // ByteArrayOutputStream baos = new ByteArrayOutputStream();

          // log("git archive", commit.abbreviate(7).name());
    
          // git().archive()
          //   .setFormat("tar")
          //   .setOutputStream(baos)
          //   .setTree(commit)
          //   .call();
    
          // Path tmpDir = Files.createTempDirectory("xbuild");
          // log(tmpDir);
    
          // ByteArrayInputStream in = new ByteArrayInputStream(baos.toByteArray());
    
          // untar(in, tmpDir);
    
          // invoke xbuildfile
          if (new File(workTree().toFile(), "xbuildfile").exists())
            Posix.run(workTree(), env, "./xbuildfile");
          else if (new File(workTree().toFile(), ".xbuild").exists())
            Posix.run(workTree(), env, "./.xbuild"); // legacy
    
          // run deploy script, e.g., xdeploy-dev
          for (String script : scripts) {
            File file = new File(workTree().toFile(), script);
            if (file.exists()) {
              if (file.isFile())
                Posix.run(workTree(), env, String.format("./%s", script));
            }
          }
        }
      }

    } catch (Exception e) {
      System.err.println(e.toString());
      exit(1);
    }

  }

  private String latest(Set<String> numbers) {
    int number = 0;
    for (String s : numbers)
      number = Math.max(number, Integer.parseInt(s));
    return ""+number;
  }
  
  private BiMap<String, RevCommit> reverse(BiMap<String, RevCommit> input) {
    BiMap<String, RevCommit> output = HashBiMap.create();;
    for (Map.Entry<String, RevCommit> entry : input.entrySet())
      output.put(""+(input.size()-Integer.parseInt(entry.getKey())), entry.getValue());
    return output;
  }

  /**
	 * untar
	 * 
	 * @param in
	 * @param outputPath
	 * @throws Exception
	 */
  private void untar(InputStream in, Path outputPath) throws Exception {
    TarArchiveEntry entry;
    TarArchiveInputStream tar = new TarArchiveInputStream(in);
    while ((entry = tar.getNextTarEntry()) != null) {
      Path entryPath = outputPath.resolve(entry.getName());
      if (entry.isDirectory()) {
        Files.createDirectories(entryPath);
      } else {
        Files.createDirectories(entryPath.getParent());
        Files.copy(tar, entryPath);
        Files.setPosixFilePermissions(entryPath, Posix.perms(entry.getMode()));
      }
    }
  }

  private void exit(int exitCode) {
    System.exit(SpringApplication.exit(context, () -> exitCode));
  }

	private void log(Object... args) {
    if (verbose)
  		new LogHelper(this).log(args);
	}

}
