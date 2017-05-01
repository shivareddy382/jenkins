/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.scm;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import hudson.util.AdaptedIterator;
import jenkins.triggers.SCMTriggerItem;
import jenkins.util.SystemProperties;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @since FIXME
 */
public abstract class RunWithSCMMixIn<JobT extends Job<JobT, RunT> & Queue.Task,
        RunT extends Run<JobT, RunT> & RunWithSCMMixIn.RunWithSCM<JobT, RunT> & Queue.Executable> {
    /**
     * Set if we want the blame information to flow from upstream to downstream build.
     */
    private static final boolean upstreamCulprits = SystemProperties.getBoolean("hudson.upstreamCulprits");

    protected abstract RunT asRun();

    public abstract List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeSets();

    /**
     * List of users who committed a change since the last non-broken build till now.
     *
     * <p>
     * This list at least always include people who made changes in this build, but
     * if the previous build was a failure it also includes the culprit list from there.
     *
     * @return
     *      can be empty but never null.
     */
    @Exported
    @Nonnull public Set<User> getCulprits() {
        if (asRun().getCulpritIds() == null) {
            Set<User> r = new HashSet<User>();
            RunT p = asRun().getPreviousCompletedBuild();
            if (p != null && asRun().isBuilding()) {
                Result pr = p.getResult();
                if (pr != null && pr.isWorseThan(Result.SUCCESS)) {
                    // we are still building, so this is just the current latest information,
                    // but we seems to be failing so far, so inherit culprits from the previous build.
                    // isBuilding() check is to avoid recursion when loading data from old Hudson, which doesn't record
                    // this information
                    r.addAll(p.getCulprits());
                }
            }
            for (ChangeLogSet<? extends ChangeLogSet.Entry> c : getChangeSets()) {
                for (ChangeLogSet.Entry e : c)
                    r.add(e.getAuthor());
            }

            if (p instanceof AbstractBuild && upstreamCulprits) {
                // If we have dependencies since the last successful build, add their authors to our list
                if (p.getPreviousNotFailedBuild() != null) {
                    Map<AbstractProject, AbstractBuild.DependencyChange> depmap =
                            ((AbstractBuild<?,?>) p).getDependencyChanges((AbstractBuild<?,?>)p.getPreviousSuccessfulBuild());
                    for (AbstractBuild.DependencyChange dep : depmap.values()) {
                        for (AbstractBuild<?, ?> b : dep.getBuilds()) {
                            for (ChangeLogSet.Entry entry : b.getChangeSet()) {
                                r.add(entry.getAuthor());
                            }
                        }
                    }
                }
            }

            return r;
        }

        return new AbstractSet<User>() {
            public Iterator<User> iterator() {
                return new AdaptedIterator<String,User>(asRun().getCulpritIds().iterator()) {
                    protected User adapt(String id) {
                        return User.get(id);
                    }
                };
            }

            public int size() {
                return asRun().getCulpritIds().size();
            }
        };
    }

    /**
     * Returns true if this user has made a commit to this build.
     */
    public boolean hasParticipant(User user) {
        for (ChangeLogSet<? extends ChangeLogSet.Entry> c : getChangeSets()) {
            for (ChangeLogSet.Entry e : c)
                try {
                    if (e.getAuthor() == user)
                        return true;
                } catch (RuntimeException re) {
                    LOGGER.log(Level.INFO, "Failed to determine author of changelog " + e.getCommitId() + "for " + asRun().getParent().getDisplayName() + ", " + asRun().getDisplayName(), re);
                }
        }
        return false;
    }

    public interface RunWithSCM<JobT extends Job<JobT, RunT> & Queue.Task,
            RunT extends Run<JobT, RunT> & RunWithSCM<JobT,RunT> & Queue.Executable> {
        @Nonnull
        List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeSets();

        @Nonnull
        Set<User> getCulprits();

        @CheckForNull
        Set<String> getCulpritIds();

        RunWithSCMMixIn<JobT,RunT> getRunWithSCMMixIn();

        boolean hasParticipant(User user);
    }

    private static final Logger LOGGER = Logger.getLogger(RunWithSCMMixIn.class.getName());
}
