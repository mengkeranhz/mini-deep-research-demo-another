package org.example;

import org.example.agent.ResearchAgent;
import org.example.config.ResearchProperties;
import org.example.skill.SkillRegistry;
import org.example.state.AgentContext;
import org.example.state.RunArchive;
import org.example.tools.FetchService;
import org.example.tools.SearchService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Scanner;

@Component
@EnableConfigurationProperties(ResearchProperties.class)
public class ResearchCommandLineRunner implements ApplicationRunner {

    private final ChatModel chatModel;
    private final ResearchProperties properties;
    private final SkillRegistry skills;
    private final SearchService searchService;
    private final FetchService fetchService;

    public ResearchCommandLineRunner(
            ChatModel chatModel,
            ResearchProperties properties,
            SkillRegistry skills,
            SearchService searchService,
            FetchService fetchService
    ) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.skills = skills;
        this.searchService = searchService;
        this.fetchService = fetchService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String query = args.getOptionValues("query") == null || args.getOptionValues("query").isEmpty()
                ? String.join(" ", args.getNonOptionArgs())
                : args.getOptionValues("query").getFirst();
        if (query == null || query.isBlank()) {
            System.out.print("请输入研究问题: ");
            query = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8).nextLine();
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("研究问题不能为空");
        }

        AgentContext context = new AgentContext(new RunArchive(Path.of(properties.archiveRoot())));
        ResearchAgent agent = new ResearchAgent(
                chatModel,
                properties.maxRounds(),
                properties.contextTokenThreshold(),
                context,
                skills,
                searchService,
                fetchService,
                Path.of(properties.projectRoot()),
                properties.tavilyMaxResults()
        );
        String result = agent.run(query.strip());
        System.out.println("\n================ 最终结果 ================\n");
        System.out.println(result);
        System.out.println("\n归档目录: " + context.archive().directory());
    }

}
