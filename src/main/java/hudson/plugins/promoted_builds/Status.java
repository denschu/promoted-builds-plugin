package hudson.plugins.promoted_builds;

import hudson.EnvVars;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause.UserCause;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.Result;
import hudson.plugins.promoted_builds.conditions.ManualCondition;
import hudson.util.Iterators;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Promotion status of a build wrt a specific {@link PromotionProcess}.
 *
 * @author Kohsuke Kawaguchi
 * @see PromotedBuildAction#statuses
 */
@ExportedBean
public final class Status {
    /**
     * Matches with {@link PromotionProcess#name}.
     */
    public final String name;

    private final PromotionBadge[] badges;

    /**
     * When did the build qualify for a promotion?
     */
    public final Calendar timestamp = new GregorianCalendar();

    /**
     * If the build is successfully promoted, the build number of {@link Promotion}
     * that represents that record.
     *
     * -1 to indicate that the promotion was not successful yet. 
     */
    private int promotion = -1;

    /**
     * Bulid numbers of {@link Promotion}s that are attempted.
     * If {@link Promotion} fails, this field can have multiple values.
     * Sorted in the ascending order.
     */
    private List<Integer> promotionAttempts = new ArrayList<Integer>();

    /*package*/ transient PromotedBuildAction parent;

    public Status(PromotionProcess process, Collection<? extends PromotionBadge> badges) {
        this.name = process.getName();
        this.badges = badges.toArray(new PromotionBadge[badges.size()]);
    }

    @Exported
    public String getName() {
        return name;
    }

    /**
     * Gets the parent {@link Status} that owns this object.
     */
    public PromotedBuildAction getParent() {
    	if (parent==null){
    		parent=getTarget().getAction(PromotedBuildAction.class);
    	}
        return parent;
    }

    /**
     * Gets the {@link PromotionProcess} that this object deals with.
     */
    @Exported
    public PromotionProcess getProcess() {
        assert parent != null : name;
        AbstractProject<?,?> project = parent.getProject();
        assert project != null : parent;
        JobPropertyImpl jp = project.getProperty(JobPropertyImpl.class);
        if(jp==null)    return null;
        return jp.getItem(name);
    }

    /**
     * Gets the icon that should represent this promotion (that is potentially attempted but failed.)
     */
    public String getIcon(String size) {
        String baseName;

        PromotionProcess p = getProcess();
        if (p == null) {
            // promotion process undefined (perhaps deleted?). fallback to the default icon
            baseName = "star-gold";
        } else {
            Promotion l = getLast();
            if (l!=null && l.getResult()!= Result.SUCCESS) {
              return Jenkins.RESOURCE_PATH+"/images/"+size+"/error.png";
            }
            baseName = p.getIcon();
        }
        return Jenkins.RESOURCE_PATH+"/plugin/promoted-builds/icons/"+size+"/"+ baseName +".png";
    }

    /**
     * Gets the build that was qualified for a promotion.
     */
    public AbstractBuild<?,?> getTarget() {
        return getParent().owner;
    }

    /**
     * Called by {@link Promotion} to allow status to contribute environment variables.
     *
     * @param build
     *      The calling build. Never null.
     * @param env
     *      Environment variables should be added to this map.
     */
    public void buildEnvVars(AbstractBuild<?,?> build, EnvVars env) {
        for (PromotionBadge badge : badges) {
            badge.buildEnvVars(build, env);
        }
    }

    /**
     * Gets the string that says how long since this promotion had happened.
     *
     * @return
     *      string like "3 minutes" "1 day" etc.
     */
    public String getTimestampString() {
        long duration = new GregorianCalendar().getTimeInMillis()-timestamp.getTimeInMillis();
        return Util.getTimeSpanString(duration);
    }

    /**
     * Gets the string that says how long did it toook for this build to be promoted.
     */
    public String getDelayString(AbstractBuild<?,?> owner) {
        long duration = timestamp.getTimeInMillis() - owner.getTimestamp().getTimeInMillis() - owner.getDuration();
        return Util.getTimeSpanString(duration);
    }

    public boolean isFor(PromotionProcess process) {
        return process.getName().equals(this.name);
    }

    /**
     * Returns the {@link Promotion} object that represents the successful promotion.
     *
     * @return
     *      null if the promotion has never been successful, or if it was but
     *      the record is already lost.
     */
    public Promotion getSuccessfulPromotion(JobPropertyImpl jp) {
        if(promotion>=0) {
            PromotionProcess p = jp.getItem(name);
            if(p!=null)
                return p.getBuildByNumber(promotion);
        }
        return null;
    }

    /**
     * Returns true if the promotion was successfully completed.
     */
    public boolean isPromotionSuccessful() {
        return promotion>=0;
    }

    /**
     * Returns true if at least one {@link Promotion} activity is attempted.
     * False if none is executed yet (this includes the case where it's in the queue.)
     */
    public boolean isPromotionAttempted() {
        return !promotionAttempts.isEmpty();
    }

    /**
     * Returns true if the promotion for this is pending in the queue,
     * waiting to be executed.
     */
    public boolean isInQueue() {
        PromotionProcess p = getProcess();
        return p!=null && p.isInQueue(getTarget());
    }

    /**
     * Gets the badges indicating how did a build qualify for a promotion.
     */
    @Exported
    public List<PromotionBadge> getBadges() {
        return Arrays.asList(badges);
    }

    /**
     * Called when a new promotion attempts for this build starts.
     */
    /*package*/ void addPromotionAttempt(Promotion p) {
        promotionAttempts.add(p.getNumber());
    }

    /**
     * Called when a promotion succeeds.
     */
    /*package*/ void onSuccessfulPromotion(Promotion p) {
        promotion = p.getNumber();
    }

//
// web bound methods
//

    /**
     * Gets the last successful {@link Promotion}.
     */
    public Promotion getLastSuccessful() {
        PromotionProcess p = getProcess();
        for( Integer n : Iterators.reverse(promotionAttempts) ) {
            Promotion b = p.getBuildByNumber(n);
            if(b!=null && b.getResult()== Result.SUCCESS)
                return b;
        }
        return null;
    }

    /**
     * Gets the last successful {@link Promotion}.
     */
    public Promotion getLastFailed() {
        PromotionProcess p = getProcess();
        for( Integer n : Iterators.reverse(promotionAttempts) ) {
            Promotion b = p.getBuildByNumber(n);
            if(b!=null && b.getResult()!=Result.SUCCESS)
                return b;
        }
        return null;
    }

    public Promotion getLast() {
        PromotionProcess p = getProcess();
        for( Integer n : Iterators.reverse(promotionAttempts) ) {
            Promotion b = p.getBuildByNumber(n);
            if(b!=null)
                return b;
        }
        return null;
    }

    @Restricted(NoExternalUse.class)
    public Boolean isLastAnError() {
      Promotion l = getLast();
      return (l != null && l.getResult() != Result.SUCCESS);
    }


    /**
     * Gets all the promotion builds.
     */
    @Exported
    public List<Promotion> getPromotionBuilds() {
        List<Promotion> builds = new ArrayList<Promotion>();
        PromotionProcess p = getProcess();
        if (p!=null) {
            for( Integer n : Iterators.reverse(promotionAttempts) ) {
                Promotion b = p.getBuildByNumber(n);
                if (b != null) {
                    builds.add(b);
                }
            }
        }
        return builds;
    }

    /**
     * Gets the promotion build by build number.
     *
     * @param number build number
     * @return promotion build
     */
    public Promotion getPromotionBuild(int number) {
        PromotionProcess p = getProcess();
        return p.getBuildByNumber(number);
    }

    public boolean isManuallyApproved(){
    	ManualCondition manualCondition=(ManualCondition) getProcess().getPromotionCondition(ManualCondition.class.getName());
    	return manualCondition!=null;
    }
    /**
     * Schedules a new build.
     */
    public void doBuild(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        
        ManualCondition manualCondition = (ManualCondition) getProcess().getPromotionCondition(ManualCondition.class.getName());
        
        if(!getTarget().hasPermission(Promotion.PROMOTE)) {
            if (manualCondition == null || (!manualCondition.getUsersAsSet().isEmpty() && !manualCondition.isInUsersList()
                    && !manualCondition.isInGroupList()))
                return;
        }
        
        JSONObject formData = req.getSubmittedForm();
        
        List<ParameterValue> paramValues=null;
        if (formData!=null){
            paramValues = new ArrayList<ParameterValue>();
            if (manualCondition!=null){
            	List<ParameterDefinition> parameterDefinitions=manualCondition.getParameterDefinitions();
                if (parameterDefinitions != null && !parameterDefinitions.isEmpty()) {
                    JSONArray a = JSONArray.fromObject(formData.get("parameter"));

                    for (Object o : a) {
                        JSONObject jo = (JSONObject) o;
                        String name = jo.getString("name");

                        ParameterDefinition d = manualCondition.getParameterDefinition(name);
                        if (d==null)
                            throw new IllegalArgumentException("No such parameter definition: " + name);

                        paramValues.add(d.createValue(req, jo));
                    }
                }
            }
        }
        if (paramValues==null){
        	paramValues = new ArrayList<ParameterValue>();
        }
        Future<Promotion> f = getProcess().scheduleBuild2(getTarget(), new UserCause(), paramValues);
        if (f==null)
            LOGGER.warning("Failing to schedule the promotion of "+getTarget());
        // TODO: we need better visual feed back so that the user knows that the build happened.
        rsp.forwardToPreviousPage(req);
    }

    private static final Logger LOGGER = Logger.getLogger(Status.class.getName());
}
