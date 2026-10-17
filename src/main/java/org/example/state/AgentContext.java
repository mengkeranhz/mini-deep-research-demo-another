package org.example.state;

import org.example.skill.Skill;

/** 单次运行的聚合状态。ResearchAgent 为每次请求创建一个新实例。 */
public final class AgentContext {

    private final TaskStore tasks = new TaskStore();
    private final FactsStore facts = new FactsStore();
    private final SearchLog searches = new SearchLog();
    private final RunArchive archive;
    private Skill activeSkill;
    private String bestAnswer;
    private String pendingFinalDefects;
    private boolean finalAccepted;

    public AgentContext(RunArchive archive) {
        this.archive = archive;
    }

    public TaskStore tasks() {
        return tasks;
    }

    public FactsStore facts() {
        return facts;
    }

    public SearchLog searches() {
        return searches;
    }

    public RunArchive archive() {
        return archive;
    }

    public Skill activeSkill() {
        return activeSkill;
    }

    public void setActiveSkill(Skill skill) {
        this.activeSkill = skill;
    }

    public String bestAnswer() {
        return bestAnswer;
    }

    public void setBestAnswer(String bestAnswer) {
        this.bestAnswer = bestAnswer;
    }

    public String pendingFinalDefects() {
        return pendingFinalDefects;
    }

    public void setPendingFinalDefects(String defects) {
        this.pendingFinalDefects = defects;
    }

    public boolean isFinalAccepted() {
        return finalAccepted;
    }

    public void setFinalAccepted(boolean finalAccepted) {
        this.finalAccepted = finalAccepted;
    }
}
