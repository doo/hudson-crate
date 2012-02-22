(ns pallet.crate.hudson-test
  (:use pallet.crate.hudson)
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.user :as user]
   [pallet.build-actions :as build-actions]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.maven :as maven]
   [pallet.crate.network-service :as network-service]
   [pallet.crate.tomcat :as tomcat]
   [pallet.live-test :as live-test]
   [pallet.parameter-test :as parameter-test]
   [pallet.phase :as phase]
   [pallet.stevedore :as stevedore]
   [pallet.template :only [apply-templates]]
   [pallet.utils :as utils]
   [net.cgrand.enlive-html :as xml])
  (:use clojure.test
        pallet.test-utils
        [pallet.action.package :only [package package-manager]]
        [pallet.stevedore :only [script]]))

(def parameters {:host
                 {:id
                  {:tomcat
                   {:default
                    {:owner "tomcat6"
                     :group "tomcat6"
                     :config-path "/etc/tomcat6/"
                     :base "/var/lib/tomcat6/"
                     :deploy "/var/lib/tomcat6/webapps"}}}}})

(deftest hudson-tomcat-test
  (is (= (first
          (build-actions/build-actions
           {:parameters (assoc-in parameters [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"})}
           (directory/directory
            "/var/lib/hudson" :owner "root" :group "tomcat6" :mode "0775")
           (directory/directories
            ["/var/lib/hudson/plugins" "/var/lib/hudson/jobs"
             "/var/lib/hudson/fingerprints"]
            :owner "tomcat6" :group "tomcat6" :mode "0775")
           (remote-file/remote-file
            "/var/lib/hudson/hudson.war"
            :url (str hudson-download-base-url "latest/jenkins.war")
            :md5 nil)
           (tomcat/policy
            99 "hudson"
            {(str "file:${catalina.base}/webapps/hudson/-")
             ["permission java.security.AllPermission"]
             (str "file:/var/lib/hudson/-")
             ["permission java.security.AllPermission"]})
           (tomcat/application-conf
            "hudson"
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>
 <Context
 privileged=\"true\"
 path=\"/hudson\"
 allowLinking=\"true\"
 swallowOutput=\"true\"
 >
 <Environment
 name=\"HUDSON_HOME\"
 value=\"/var/lib/hudson\"
 type=\"java.lang.String\"
 override=\"false\"/>
 </Context>")
           (tomcat/deploy "hudson" :remote-file "/var/lib/hudson/hudson.war")))
         (first
          (build-actions/build-actions
           {:parameters parameters}
           (tomcat-deploy)
           (parameter-test/parameters-test
            [:host :id :hudson :data-path] "/var/lib/hudson"
            [:host :id :hudson :user] "tomcat6"
            [:host :id :hudson :group] "tomcat6"))))))

(deftest determine-scm-type-test
  (is (= :git (determine-scm-type ["http://project.org/project.git"]))))

(deftest normalise-scms-test
  (is (= [["http://project.org/project.git"]]
         (normalise-scms ["http://project.org/project.git"]))))

(deftest output-scm-for-git-test
  (is (= "<scm class=\"hudson.plugins.git.GitSCM\">\n  <remoteRepositories>\n    <org.spearce.jgit.transport.RemoteConfig>\n      <string>origin</string>\n      <int>5</int>\n      <string>fetch</string>\n      <string>+refs/heads/*:refs/remotes/origin/*</string>\n      <string>receivepack</string>\n      <string>git-upload-pack</string>\n      <string>uploadpack</string>\n      <string>git-upload-pack</string>\n      <string>url</string>\n      <string>http://project.org/project.git</string>\n      <string>tagopt</string>\n      <string></string>\n    </org.spearce.jgit.transport.RemoteConfig>\n  </remoteRepositories>\n  <branches>\n    <hudson.plugins.git.BranchSpec>\n      <name>*</name>\n    </hudson.plugins.git.BranchSpec>\n  </branches>\n  <mergeOptions></mergeOptions>\n  <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>\n  <submoduleCfg class=\"list\"></submoduleCfg>\n</scm>"
         (apply str
                (xml/emit*
                 (output-scm-for
                  :git {:server {:group-name :b :image {:os-family :ubuntu}}}
                  "http://project.org/project.git" {}))))))

(deftest output-scm-for-svn-test
  (is (= "<scm class=\"hudson.scm.SubversionSCM\">\n  <locations>\n    <hudson.scm.SubversionSCM_-ModuleLocation>\n      <remote>http://project.org/svn/project</remote>\n    </hudson.scm.SubversionSCM_-ModuleLocation>\n  </locations>\n  <useUpdate>false</useUpdate>\n  <doRevert>false</doRevert>\n  \n  <excludedRegions></excludedRegions>\n  <includedRegions></includedRegions>\n  <excludedUsers></excludedUsers>\n  <excludedRevprop></excludedRevprop>\n  <excludedCommitMessages></excludedCommitMessages>\n</scm>"
         (apply str
                (xml/emit*
                 (output-scm-for
                  :svn {:server {:group-name :b :image {:os-family :ubuntu}}}
                  ["http://project.org/svn/project"] {})))))
  (is (= "<scm class=\"hudson.scm.SubversionSCM\">\n  <locations>\n    <hudson.scm.SubversionSCM_-ModuleLocation>\n      <remote>http://project.org/svn/project/branch/a</remote><remote>http://project.org/svn/project/branch/a</remote>\n    </hudson.scm.SubversionSCM_-ModuleLocation><hudson.scm.SubversionSCM_-ModuleLocation>\n      <remote>http://project.org/svn/project/branch/b</remote><remote>http://project.org/svn/project/branch/b</remote>\n    </hudson.scm.SubversionSCM_-ModuleLocation>\n  </locations>\n  <useUpdate>false</useUpdate>\n  <doRevert>false</doRevert>\n  <browser class=\"c\"><url>url</url></browser>\n  <excludedRegions></excludedRegions>\n  <includedRegions></includedRegions>\n  <excludedUsers></excludedUsers>\n  <excludedRevprop></excludedRevprop>\n  <excludedCommitMessages></excludedCommitMessages>\n</scm>"
         (apply str
                (xml/emit*
                 (output-scm-for
                  :svn {:server {:group-name :b :image {:os-family :ubuntu}}}
                  ["http://project.org/svn/project/branch/"]
                  {:branches ["a" "b"]
                   :browser {:class "c" :url "url"}}))))))

(deftest credential-entry-test
  (is (= [:entry {}
          [:string {} "<http://server.com:80>"]
          [:hudson.scm.SubversionSCM_-DescriptorImpl_-PasswordCredential {}
           [:userName {} "u"]
           [:password {} "cA==\r\n"]]]
         (credential-entry
          ["<http://server.com:80>" {:user-name "u" :password "p"}]))))

(deftest credential-store-test
  (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hudson.scm.PerJobCredentialStore><credentials class=\"hashtable\"><entry><string>&lt;http://server.com:80&gt;</string><hudson.scm.SubversionSCM_-DescriptorImpl_-PasswordCredential><userName>u</userName><password>cA==\r\n</password></hudson.scm.SubversionSCM_-DescriptorImpl_-PasswordCredential></entry></credentials></hudson.scm.PerJobCredentialStore>"
         (credential-store
          {"<http://server.com:80>" {:user-name "u" :password "p"}}))))

(deftest plugin-property-test
  (is (= {:tag "hudson.plugins.jira.JiraProjectProperty"
          :content [{:content "http://jira.somewhere.com/", :tag "siteName"}]}
         (plugin-property [:jira {:siteName "http://jira.somewhere.com/"}])))
  (is (= {:tag "hudson.security.AuthorizationMatrixProperty"
          :content [{:tag "permission" :content "hudson.model.Item.Read:me"}
                    {:tag "permission" :content "hudson.model.Item.Build:me"}]}
         (plugin-property [:authorization-matrix
                           [{:user "me"
                             :permissions #{:item-build :item-read}}]]))))

(deftest hudson-job-test
  (let [n (core/group-spec "n")]
    (is (= (first
            (build-actions/build-actions
             {}
             (directory/directory
              "/var/lib/hudson/jobs/project" :p true
              :owner "tomcat6" :group "tomcat6" :mode "0775")
             (remote-file/remote-file
              "/var/lib/hudson/jobs/project/config.xml"
              :content "<?xml version='1.0' encoding='utf-8'?>\n<maven2-moduleset>\n  <actions></actions>\n  <description></description>\n  <logRotator>\n    <daysToKeep>-1</daysToKeep>\n    <numToKeep>-1</numToKeep>\n    <artifactDaysToKeep>-1</artifactDaysToKeep>\n    <artifactNumToKeep>-1</artifactNumToKeep>\n  </logRotator>\n  <keepDependencies>false</keepDependencies>\n  <properties><hudson.plugins.disk__usage.DiskUsageProperty></hudson.plugins.disk__usage.DiskUsageProperty></properties>\n  <scm class=\"hudson.plugins.git.GitSCM\">\n  <remoteRepositories>\n    <org.spearce.jgit.transport.RemoteConfig>\n      <string>origin</string>\n      <int>5</int>\n      <string>fetch</string>\n      <string>+refs/heads/*:refs/remotes/origin/*</string>\n      <string>receivepack</string>\n      <string>git-upload-pack</string>\n      <string>uploadpack</string>\n      <string>git-upload-pack</string>\n      <string>url</string>\n      <string>http://project.org/project.git</string>\n      <string>tagopt</string>\n      <string></string>\n    </org.spearce.jgit.transport.RemoteConfig>\n  </remoteRepositories>\n  <branches>\n    <hudson.plugins.git.BranchSpec>\n      <name>origin/master</name>\n    </hudson.plugins.git.BranchSpec>\n  </branches>\n  <mergeOptions></mergeOptions>\n  <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>\n  <submoduleCfg class=\"list\"></submoduleCfg>\n</scm>\n  <canRoam>true</canRoam>\n  <disabled>false</disabled>\n  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>\n  \n  <triggers class=\"vector\"><hudson.triggers.SCMTrigger><spec>*/15 * * * *</spec></hudson.triggers.SCMTrigger></triggers>\n  <concurrentBuild>false</concurrentBuild>\n  <rootModule>\n    <groupId>project</groupId>\n    <artifactId>artifact</artifactId>\n  </rootModule>\n  <goals>clojure:test</goals>\n  <defaultGoals></defaultGoals>\n  \n  <mavenOpts>-Dx=y</mavenOpts>\n  <mavenName>base maven</mavenName>\n  <aggregatorStyleBuild>true</aggregatorStyleBuild>\n  <incrementalBuild>false</incrementalBuild>\n  <usePrivateRepository>false</usePrivateRepository>\n  <ignoreUpstremChanges>false</ignoreUpstremChanges>\n  <archivingDisabled>false</archivingDisabled>\n  <reporters></reporters>\n  <publishers></publishers>\n  <buildWrappers></buildWrappers>\n</maven2-moduleset>"
              :owner "root" :group "tomcat6" :mode "0664")))
           (first
            (build-actions/build-actions
             {:server {:image {:os-family :ubuntu}}
              :parameters {:host {:id {:hudson {:data-path "/var/lib/hudson"
                                                :user "tomcat6"
                                                :group "tomcat6"
                                                :owner "root"}}}}}
             (job
              :maven2 "project"
              :maven-opts "-Dx=y"
              :branches ["origin/master"]
              :scm ["http://project.org/project.git"]
              :triggers {:scm-trigger "*/15 * * * *"}
              :properties {:disk-usage {}})))))))


(deftest hudson-maven-xml-test
  (let [test-node (core/group-spec "test-node" :image {:os-family :ubuntu})]
    (is (= "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>name</name>\n      <home>/var/lib/hudson/tools/name</home>\n      \n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>"
           (apply str (hudson-maven-xml
                       {:server test-node}
                       "/var/lib/hudson"
                       [["name" "2.2.0"]]))))))

(deftest hudson-ant-xml-test
  (let [test-node (core/group-spec "test-node" :image {:os-family :ubuntu})]
    (is (= "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Ant_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Ant_-AntInstallation>\n      <name>name</name>\n      <home>/some/path</home>\n      <properties>a=1\n</properties>\n    </hudson.tasks.Ant_-AntInstallation>\n  </installations>\n</hudson.tasks.Ant_-DescriptorImpl>"
           (apply str (hudson-ant-xml
                       {:server test-node}
                       "/var/lib/hudson"
                       [["name" "/some/path" {:a 1}]]))))))

(deftest hudson-maven-test
  (is (= (first
          (build-actions/build-actions
           {}
           (maven/download
            :maven-home "/var/lib/hudson/tools/default_maven"
            :version "2.2.0"
            :owner "root"
            :group "tomcat6")
           (directory/directory
            "/usr/share/tomcat6/.m2" :group "tomcat6" :mode "g+w")
           (remote-file/remote-file
            "/var/lib/hudson/hudson.tasks.Maven.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>default maven</name>\n      <home>/var/lib/hudson/tools/default_maven</home>\n      \n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>"
            :owner "root"
            :group "tomcat6"
            :mode "0664")))
         (first
          (build-actions/build-actions
           {:server {:image {:os-family :ubuntu}}
            :parameters {:host
                         {:id {:hudson {:user "tomcat6" :group "tomcat6"
                                        :owner "root"
                                        :data-path "/var/lib/hudson"}}}}}
           (maven "default maven" "2.2.0"))))))

(deftest hudson-ant-test
  (is (= (first
          (build-actions/build-actions
           {}
           (remote-file/remote-file
            "/var/lib/hudson/hudson.tasks.Ant.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Ant_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Ant_-AntInstallation>\n      <name>name</name>\n      <home>/some/path</home>\n      <properties>a=1\n</properties>\n    </hudson.tasks.Ant_-AntInstallation>\n  </installations>\n</hudson.tasks.Ant_-DescriptorImpl>"
            :owner "root"
            :group "tomcat6"
            :mode "0664")))
         (first
          (build-actions/build-actions
           {:server {:image {:os-family :ubuntu}}
            :parameters {:host
                         {:id {:hudson {:user "tomcat6" :group "tomcat6"
                                        :owner "root"
                                        :data-path "/var/lib/hudson"}}}}}
           (ant-config "name" "/some/path" {:a 1}))))))

(deftest plugin-test
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory
            "/var/lib/hudson/plugins"
            :owner "tomcat6" :group "tomcat6" :mode "0775")
           (user/user "tomcat6" :action :manage :comment "hudson")
           (remote-file/remote-file
            "/var/lib/hudson/plugins/git.hpi"
            :group "tomcat6" :mode "0664"
            :url (default-plugin-path :git :latest))))
         (first
          (build-actions/build-actions
           {:parameters (assoc-in parameters
                                  [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"
                                   :group "tomcat6"
                                   :user "tomcat6"})}
           (plugin :git)))))
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory
            "/var/lib/hudson/plugins"
            :owner "tomcat6" :group "tomcat6" :mode "0775")
           (user/user "tomcat6" :action :manage :comment "hudson")
           (remote-file/remote-file
            "/var/lib/hudson/plugins/git.hpi"
            :group "tomcat6" :mode "0664"
            :url (default-plugin-path :git "1.15"))))
         (first
          (build-actions/build-actions
           {:parameters (assoc-in parameters
                                  [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"
                                   :group "tomcat6"
                                   :user "tomcat6"})}
           (plugin :git :version "1.15"))))))

(deftest invocation
  (is (build-actions/build-actions
       {:parameters parameters}
       (tomcat-deploy)
       (parameter-test/parameters-test
        [:host :id :hudson :user] "tomcat6"
        [:host :id :hudson :group] "tomcat6")
       (maven "name" "2.2.1")
       (ant-config "name" "/some/path" {:a 1})
       (job :maven2 "job")
       (job :ant "job" :ant-tasks [{:target "a"
                                    :antName "name"
                                    :buildFile "some/file.xml"
                                    :properties {:a 1}}])
       (plugin :git)))
  (is (build-actions/build-actions
       {:parameters parameters}
       (tomcat-deploy)
       (tomcat-undeploy))))

(deftest trigger-test
  (testing "scmtrigger"
    (is (= (str "<hudson.triggers.SCMTrigger>"
                "<spec>123</spec>"
                "</hudson.triggers.SCMTrigger>")
           (trigger-config [:scm-trigger "123"]))))
  (testing "startup trigger"
    (is
     (=
      (str "<org.jvnet.hudson.plugins.triggers.startup.HudsonStartupTrigger>"
           "<spec></spec>"
           "</org.jvnet.hudson.plugins.triggers.startup.HudsonStartupTrigger>")
      (trigger-config [:startup-trigger ""])))))

(deftest publisher-test
  (testing "artifact archiver"
    (is (= (str "<hudson.tasks.ArtifactArchiver>"
                "<artifacts>**/*.war</artifacts><latestOnly>false</latestOnly>"
                "</hudson.tasks.ArtifactArchiver>")
           (publisher-config [:artifact-archiver {:artifacts "**/*.war"}]))))
  (testing "build trigger"
    (testing "default threshold"
      (is (= (str "<hudson.tasks.BuildTrigger>"
                  "<childProjects>a,b</childProjects>"
                  "<threshold><name>SUCCESS</name>"
                  "<ordinal>0</ordinal><color>BLUE</color></threshold>"
                  "</hudson.tasks.BuildTrigger>")
             (publisher-config [:build-trigger {:child-projects "a,b"}]))))
    (testing "with unstable threshold"
      (is (= (str "<hudson.tasks.BuildTrigger>"
                  "<childProjects>a,b</childProjects>"
                  "<threshold><name>UNSTABLE</name>"
                  "<ordinal>1</ordinal><color>YELLOW</color></threshold>"
                  "</hudson.tasks.BuildTrigger>")
             (publisher-config
              [:build-trigger
               {:child-projects "a,b" :threshold :unstable}]))))))

(def unsupported [{:os-family :debian}]) ; no tomcat6

(deftest live-test
  (remote-file/set-force-overwrite true)
  (doseq [image (live-test/exclude-images live-test/*images* unsupported)]
    (live-test/test-nodes
     [compute node-map node-types]
     {:hudson
      {:image (update-in image [:min-ram] #(max (or % 0) 512))
       :count 1
       :phases {:bootstrap (phase/phase-fn
                            (automated-admin-user/automated-admin-user))
                :configure (phase/phase-fn
                            (tomcat/install :version 6)
                            (tomcat-deploy)
                            (config)
                            (plugin :git :version "1.1.5")
                            (plugin :jira :version "1.26")
                            (plugin :disk-usage :version "0.12")
                            (plugin :shelve-project-plugin :version "1.1")
                            (ant-config "antabc" "/some/path" {})
                            (job
                             :maven2 "gitjob"
                             :maven-name "default maven"
                             :scm ["git://github.com/hugoduncan/pallet.git"])
                            (job
                             :maven2 "svnjob"
                             :maven-name "default maven"
                             :scm ["http://svn.host.com/project"]
                             :subversion-credentials
                             {"somename"
                              {:user-name "u" :password "p"}})
                            (job
                             :ant "antjob"
                             :num-to-keep 1
                             :ant-tasks [{:targets "jar"
                                          :ant-name "name"
                                          :build-file "file.xml"
                                          :properties {:a 1}}]
                             :scm ["git://github.com/hugoduncan/pallet.git"])
                            (tomcat/init-service :action :restart))
                :verify (phase/phase-fn
                         ;; hudson takes a while to start up
                         (network-service/wait-for-http-status
                          "http://localhost:8080/hudson" 200
                          :max-retries 10 :url-name "hudson")
                         (exec-script/exec-checked-script
                          "check hudson installed"
                          (wget "-O-" "http://localhost:8080/hudson")
                          (wget "-O-" "http://localhost:8080/hudson/job/gitjob")
                          (wget
                           "-O-" "http://localhost:8080/hudson/job/svnjob")
                          (wget "-O-" "http://localhost:8080/hudson/job/antjob")
                          ("test"
                           (file-exists?
                            "/var/lib/hudson/jobs/svnjob/subversion.credentials"))
                          ("test"
                           (file-exists? "/var/lib/hudson/hudson.tasks.Ant.xml"))))}}}
     (core/lift (:hudson node-types) :phase :verify :compute compute))))

(deftest user-test
  (testing "generate user xml"
           (let [full "First Last"
                 hash "hash-foo-bar"
                 mail "somone@some.domain"
                 xml (hudson-user-xml
                       {:server {}}
                       {:full-name full :password-hash hash :email mail})]
             (is (.contains xml full))
             (is (.contains xml hash))
             (is (.contains xml mail)))))
