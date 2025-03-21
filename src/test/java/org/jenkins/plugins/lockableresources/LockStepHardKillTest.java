package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Executor;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepHardKillTest extends LockStepTestBase {

    @Issue("JENKINS-36479")
    @Test
    void hardKillNewBuildClearsLock(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label");

        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {"
                        + "  echo 'JOB 1 inside'\n"
                        +
                        // this looks strange, but don not panic
                        // the job will be killed later
                        // I use here sleep instead of semaphore, because the
                        // semaphore step is very instable (slowly ?)
                        "  sleep 600;"
                        + "  echo 'JOB 1 unblocked';"
                        + "}",
                true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        j.waitForMessage("JOB 1 inside", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "try {\n"
                        + "lock('resource1') {\n"
                        + "  echo 'JOB 2 inside'\n"
                        +
                        // also here use long sleep time
                        // the job will be stopped later (but not killed)
                        "  sleep 600;"
                        + "}\n"
                        + "}\n"
                        + "catch(error) {\n"
                        + "  echo 'JOB 2 unblocked';\n"
                        + "  echo 'error:' + error;\n"
                        + "}",
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();

        // Make sure that b2 is blocked on b1's lock.
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);

        // Now b2 is still sitting waiting for a lock. Create b3 and launch it to clear the
        // lock.
        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                """
                lock('resource1') {
                  echo 'JOB 3 inside'
                }
                echo 'JOB 3 unblocked'""",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();

        // Make sure that b3 is also blocked still on b1's lock.
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);
        isPaused(b3, 1, 1);

        // Kill b1 hard.
        b1.doKill();
        j.waitForMessage("Hard kill!", b1);
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.ABORTED, b1);

        // Verify that b2 gets the lock.
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b2);
        j.waitForMessage("JOB 2 inside", b2);
        j.assertLogNotContains("JOB 3 inside", b3);

        // terminate current step
        b2.doTerm();
        // Verify that b2 releases the lock and finishes successfully.
        j.waitForMessage("Lock released on resource [Resource: resource1]", b2);
        j.waitForMessage("JOB 2 unblocked", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        isPaused(b2, 1, 0);

        // Now b3 should get the lock and do its thing.
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b3);
        j.waitForMessage("Lock released on resource [Resource: resource1]", b3);
        j.waitForMessage("JOB 3 unblocked", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b3, 1, 0);
    }

    @Issue("JENKINS-40368")
    @Test
    void hardKillWithWaitingRuns(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore('wait-inside') }", true));

        WorkflowRun prevBuild = null;
        for (int i = 0; i < 3; i++) {
            WorkflowRun rNext = p.scheduleBuild2(0).waitForStart();
            if (prevBuild != null) {
                j.waitForMessage("[resource1] is locked by build " + prevBuild.getFullDisplayName(), rNext);
                isPaused(rNext, 1, 1);
                interruptTermKill(j, prevBuild);
            }

            j.waitForMessage("Trying to acquire lock on [Resource: resource1]", rNext);

            SemaphoreStep.waitForStart("wait-inside/" + (i + 1), rNext);
            isPaused(rNext, 1, 0);
            prevBuild = rNext;
        }
        SemaphoreStep.success("wait-inside/3", null);
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(prevBuild));
    }

    @Issue("JENKINS-40368")
    @Test
    void hardKillWithWaitingRunsOnLabel(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock(label: 'label1', quantity: 1) { semaphore('wait-inside')}", true));

        WorkflowRun firstPrev = null;
        WorkflowRun secondPrev = null;
        for (int i = 0; i < 3; i++) {
            WorkflowRun firstNext = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("Trying to acquire lock on", firstNext);
            WorkflowRun secondNext = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("Trying to acquire lock on", secondNext);

            if (firstPrev != null) {
                j.waitForMessage(", waiting for execution ...", firstNext);
                isPaused(firstNext, 1, 1);
                j.waitForMessage(", waiting for execution ...", secondNext);
                isPaused(secondNext, 1, 1);
            }

            interruptTermKill(j, firstPrev);
            j.waitForMessage("Lock acquired on ", firstNext);
            interruptTermKill(j, secondPrev);
            j.waitForMessage("Lock acquired on ", secondNext);

            SemaphoreStep.waitForStart("wait-inside/" + ((i * 2) + 1), firstNext);
            SemaphoreStep.waitForStart("wait-inside/" + ((i * 2) + 2), secondNext);
            isPaused(firstNext, 1, 0);
            isPaused(secondNext, 1, 0);
            firstPrev = firstNext;
            secondPrev = secondNext;
        }
        SemaphoreStep.success("wait-inside/5", null);
        SemaphoreStep.success("wait-inside/6", null);
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(firstPrev));
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(secondPrev));
    }

    private static void interruptTermKill(JenkinsRule j, WorkflowRun b) throws Exception {
        if (b != null) {
            Executor ex = b.getExecutor();
            assertNotNull(ex);
            ex.interrupt();
            j.waitForCompletion(b);
            j.assertBuildStatus(Result.ABORTED, b);
        }
    }
}
