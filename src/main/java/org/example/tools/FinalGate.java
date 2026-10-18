package org.example.tools;

import org.example.agent.PromptTemplates;
import org.example.agent.ChatModelCalls;
import org.example.state.AgentContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/** 确定性规则优先，只有必要时才调用无工具 LLM 复核。 */
public final class FinalGate {

    private final ChatModel chatModel;
    private final AgentContext context;

    public FinalGate(ChatModel chatModel, AgentContext context) {
        this.chatModel = chatModel;
        this.context = context;
    }

    public String verify(String draft) {
        String taskBaseline = context.tasks().baseline();
        String factLedger = context.facts().ledger();
        String skill = context.activeSkill() == null ? null : context.activeSkill().instructions();
        if (taskBaseline == null && factLedger == null && skill == null) {
            context.setFinalAccepted(true);
            return null;
        }

        String deterministic = context.facts().gateReport();
        if (deterministic != null) {
            context.setFinalAccepted(false);
            return "最终回答前发现以下覆盖/缺口/冲突问题，逐项处理后再提交完整答案：\n"
                    + deterministic
                    + "\n\n修复方式：已检索但标签错位的，照抄覆盖目标 dimension/period 重新入账；确实缺失的补检索；"
                    + "检索不到的用 not_found 入账并说明检索过程；冲突事实先复核。";
        }

        String critical = context.facts().criticalAssumptions();
        if (critical != null && (!draft.contains("保守主方案") || !draft.contains("升级条件"))) {
            context.setFinalAccepted(false);
            return "存在未降级的关键依赖，答案必须同时给出保守主方案与升级条件：\n" + critical;
        }

        String basis = join(taskBaseline, factLedger);
        if (skill != null) {
            basis = join(basis, "# 已加载技能终检要求\n" + skill);
        }
        if (critical != null) {
            basis = join(basis, "# 关键依赖降级核对\n" + critical);
        }

        List<Message> messages = List.of(
                new SystemMessage(PromptTemplates.VERIFIER_PROMPT),
                new UserMessage("校验依据：\n" + basis + "\n\n待校验终稿：\n" + draft)
        );
        String verdict;
        try {
            verdict = ChatModelCalls.call(chatModel, new Prompt(messages)).getResult().getOutput().getText();
        } catch (Exception e) {
            context.setFinalAccepted(false);
            return "最终校验器调用失败（这不是答案内容 FAIL）：" + e.getMessage()
                    + "\n请压缩重复叙述、保留事实和关键来源后重新提交。";
        }
        if (verdict == null || verdict.isBlank()) {
            context.setFinalAccepted(false);
            return "最终校验器未产出 PASS/FAIL（这不是答案内容 FAIL）。请压缩终稿体积后重新提交完整答案。";
        }
        boolean pass = verdict.strip().toUpperCase().startsWith("PASS");
        context.setFinalAccepted(pass);
        return pass ? null
                : "回答未通过最终校验，逐条修复后重新提交完整答案：\n" + verdict
                + "\n注意：标签错位不要重新检索，照抄覆盖目标字符串重新入账即可。";
    }

    private static String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + "\n\n" + right;
    }
}
