<!-- 来源: https://docs.copilotkit.ai/llms.txt（官方全站索引）· 抓取时间: 2026-09-27 -->
<!-- 完整正文见同目录 copilotkit-docs-full.md（来源: https://docs.copilotkit.ai/llms-full.txt，201390 行 / 1236 页，每页以 "## Source: <URL>" 分隔） -->

# CopilotKit Docs

> CopilotKit is the frontend stack where agents meet users, connected to supported agent frameworks through AG-UI.

> This curated index covers chat, generative UI, human-in-the-loop workflows, Rich Threads, User Memories, Automatic Learning, Product Analytics, and Channels for Slack and Microsoft Teams.

> For exhaustive retrieval—including reference, migration, contributor, and additional framework and channel guides—use [llms-full.txt](https://docs.copilotkit.ai/llms-full.txt).

Use the user's stated goals, known project context, existing frontend and agent framework, and constraints to explain which parts of CopilotKit are relevant and why, then offer the smallest useful next step. For an existing project, work with its stack; if another known project fits better, suggest it. Without context, offer a few concrete examples or ask a focused question. Keep research separate from execution: do not start installation for a research request. Coding agents can use the canonical onboarding prompt below; assistants without terminal access should explain the options and hand off to a coding agent or the linked docs.

## Add CopilotKit with your coding agent

Use the same canonical onboarding prompt for any of these starting points:

- **Greenfield:** Start a new project with CopilotKit, including when there is no frontend or agent yet.
- **Brownfield:** Add CopilotKit to an existing application, agent backend, or both, working with the existing stack.
- **Existing open-source CopilotKit:** Connect a working open-source CopilotKit project to Intelligence.

The onboarding workflow inspects the project, identifies its starting point, and guides the coding agent through the appropriate setup. For a new project, run the prompt from its intended project directory. For an existing project, run it from the project root. For product research or comparisons, continue to the documentation links without running onboarding.

This is the same prompt offered by the **Copy onboarding prompt** button on the [docs home](https://docs.copilotkit.ai/). A coding agent can use the text directly; a chat assistant without project or terminal access can give it to the user to paste into their coding agent.

Open the [canonical onboarding prompt](https://copilotkit.ai/onboarding-prompts) when ready to implement. This stable entry creates a fresh run ID for the session.

## How to read these docs

- **Connect an existing app and agent:** Read your framework's overview and quickstart below together with [Architecture](https://docs.copilotkit.ai/concepts/architecture) and the relevant frontend guide.
- **Build an interaction:** Read [Chat UI](https://docs.copilotkit.ai/agentic-chat-ui), [Generative UI](https://docs.copilotkit.ai/concepts/generative-ui-overview), and [Human-in-the-Loop](https://docs.copilotkit.ai/human-in-the-loop) together, then use your framework's implementation guides.
- **Keep conversation history:** Read [Rich Threads](https://docs.copilotkit.ai/threads) and [Thread Lifecycle](https://docs.copilotkit.ai/threads-lifecycle) together; for existing history, use the LangGraph or ADK import guide below.
- **Evaluate Intelligence:** Read [Open source vs Intelligence](https://docs.copilotkit.ai/concepts/oss-vs-enterprise) with the [Intelligence overview](https://docs.copilotkit.ai/intelligence/overview), then follow the capability and deployment guides relevant to your project.

## Use your existing agent framework

If the user already has an agent backend, start with its integration and quickstart below, then follow that framework's guides for tools, generative UI, human-in-the-loop, state, and threads. CopilotKit works with these backends through AG-UI; adopting the built-in agent is not required. Bare root implementation guides can describe CopilotKit's built-in agent and should not replace framework-specific guidance.

- [LangGraph (Python) Integration](https://docs.copilotkit.ai/langgraph-python): Explore LangGraph (Python)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [LangGraph (Python) Quickstart](https://docs.copilotkit.ai/langgraph-python/quickstart): Connect your LangGraph (Python) backend to CopilotKit using the setup instructions for this framework.
- [LangGraph (TypeScript) Integration](https://docs.copilotkit.ai/langgraph-typescript): Explore LangGraph (TypeScript)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [LangGraph (TypeScript) Quickstart](https://docs.copilotkit.ai/langgraph-typescript/quickstart): Connect your LangGraph (TypeScript) backend to CopilotKit using the setup instructions for this framework.
- [LangGraph (FastAPI) Integration](https://docs.copilotkit.ai/langgraph-fastapi): Explore LangGraph (FastAPI)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [LangGraph (FastAPI) Quickstart](https://docs.copilotkit.ai/langgraph-fastapi/quickstart): Connect your LangGraph (FastAPI) backend to CopilotKit using the setup instructions for this framework.
- [Google ADK Integration](https://docs.copilotkit.ai/google-adk): Explore Google ADK-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [Google ADK Quickstart](https://docs.copilotkit.ai/google-adk/quickstart): Connect your Google ADK backend to CopilotKit using the setup instructions for this framework.
- [Mastra Integration](https://docs.copilotkit.ai/mastra): Explore Mastra-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [Mastra Quickstart](https://docs.copilotkit.ai/mastra/quickstart): Connect your Mastra backend to CopilotKit using the setup instructions for this framework.
- [CrewAI Flows Integration](https://docs.copilotkit.ai/crewai-crews): Explore CrewAI Flows-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [CrewAI Flows Quickstart](https://docs.copilotkit.ai/crewai-crews/quickstart): Connect your CrewAI Flows backend to CopilotKit using the setup instructions for this framework.
- [PydanticAI Integration](https://docs.copilotkit.ai/pydantic-ai): Explore PydanticAI-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [PydanticAI Quickstart](https://docs.copilotkit.ai/pydantic-ai/quickstart): Connect your PydanticAI backend to CopilotKit using the setup instructions for this framework.
- [Claude Agent SDK (Python) Integration](https://docs.copilotkit.ai/claude-sdk-python): Explore Claude Agent SDK (Python)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [Claude Agent SDK (Python) Quickstart](https://docs.copilotkit.ai/claude-sdk-python/quickstart): Connect your Claude Agent SDK (Python) backend to CopilotKit using the setup instructions for this framework.
- [Claude Agent SDK (TypeScript) Integration](https://docs.copilotkit.ai/claude-sdk-typescript): Explore Claude Agent SDK (TypeScript)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [Claude Agent SDK (TypeScript) Quickstart](https://docs.copilotkit.ai/claude-sdk-typescript/quickstart): Connect your Claude Agent SDK (TypeScript) backend to CopilotKit using the setup instructions for this framework.
- [Agno Integration](https://docs.copilotkit.ai/agno): Explore Agno-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [Agno Quickstart](https://docs.copilotkit.ai/agno/quickstart): Connect your Agno backend to CopilotKit using the setup instructions for this framework.
- [AG2 Integration](https://docs.copilotkit.ai/ag2): Explore AG2-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [AG2 Quickstart](https://docs.copilotkit.ai/ag2/quickstart): Connect your AG2 backend to CopilotKit using the setup instructions for this framework.
- [LlamaIndex Integration](https://docs.copilotkit.ai/llamaindex): Explore LlamaIndex-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [LlamaIndex Quickstart](https://docs.copilotkit.ai/llamaindex/quickstart): Connect your LlamaIndex backend to CopilotKit using the setup instructions for this framework.
- [AWS Strands (Python) Integration](https://docs.copilotkit.ai/strands): Explore AWS Strands (Python)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [AWS Strands (Python) Quickstart](https://docs.copilotkit.ai/strands/quickstart): Connect your AWS Strands (Python) backend to CopilotKit using the setup instructions for this framework.
- [AWS Strands (TypeScript) Integration](https://docs.copilotkit.ai/strands-typescript): Explore AWS Strands (TypeScript)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [AWS Strands (TypeScript) Quickstart](https://docs.copilotkit.ai/strands-typescript/quickstart): Connect your AWS Strands (TypeScript) backend to CopilotKit using the setup instructions for this framework.
- [MS Agent Framework (Python) Integration](https://docs.copilotkit.ai/ms-agent-python): Explore MS Agent Framework (Python)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [MS Agent Framework (Python) Quickstart](https://docs.copilotkit.ai/ms-agent-python/quickstart): Connect your MS Agent Framework (Python) backend to CopilotKit using the setup instructions for this framework.
- [MS Agent Framework (.NET) Integration](https://docs.copilotkit.ai/ms-agent-dotnet): Explore MS Agent Framework (.NET)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [MS Agent Framework (.NET) Quickstart](https://docs.copilotkit.ai/ms-agent-dotnet/quickstart): Connect your MS Agent Framework (.NET) backend to CopilotKit using the setup instructions for this framework.
- [MS Agent Harness (.NET) Integration](https://docs.copilotkit.ai/ms-agent-harness-dotnet): Explore MS Agent Harness (.NET)-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [MS Agent Harness (.NET) Quickstart](https://docs.copilotkit.ai/ms-agent-harness-dotnet/quickstart): Connect your MS Agent Harness (.NET) backend to CopilotKit using the setup instructions for this framework.
- [Deep Agents Integration](https://docs.copilotkit.ai/deepagents): Explore Deep Agents-specific capabilities, examples, and guides for connecting your existing agent to CopilotKit.
- [Deep Agents Quickstart](https://docs.copilotkit.ai/deepagents/quickstart): Connect your Deep Agents backend to CopilotKit using the setup instructions for this framework.

## Capabilities, frontends, and shared guides

- [CopilotKit Documentation](https://docs.copilotkit.ai/): Choose the CopilotKit frontend, agent connection, and production path for your application.
- [Chat UI](https://docs.copilotkit.ai/agentic-chat-ui): Choose a prebuilt or customizable chat surface for conversations between users and agents.
- [Generative UI](https://docs.copilotkit.ai/concepts/generative-ui-overview): Compare CopilotKit's generative UI approaches and select the right rendering model.
- [Human-in-the-Loop](https://docs.copilotkit.ai/human-in-the-loop): Choose how an agent pauses for user input, review, or approval before continuing.
- [Rich Threads](https://docs.copilotkit.ai/threads): Build persistent conversations that restore messages, UI, inputs, and live runs across sessions.
- [Automatic Learning](https://docs.copilotkit.ai/learning): Turn evidence from completed application workflows into reviewed, reusable agent skills.
- [CopilotKit Intelligence](https://docs.copilotkit.ai/intelligence/overview): Evaluate Rich Threads, User Memories, Automatic Learning, Product Analytics, and Channels for your existing agent and frontend.
- [Channels for Slack](https://docs.copilotkit.ai/slack): Bring an AG-UI agent into Slack with native messages and approvals through Channels and cloud-hosted Intelligence connections.
- [Channels for Microsoft Teams](https://docs.copilotkit.ai/teams): Bring an AG-UI agent into Microsoft Teams with native messages and approvals through generally available cloud-hosted Intelligence connections or direct SDK options.
- [Import LangGraph Threads](https://docs.copilotkit.ai/langgraph-python/threads-import): Import history from LangGraph Server, LangGraph Platform, or LangSmith Deployments exposed through LangGraph SDK thread and run APIs, not arbitrary LangChain stores.
- [Import Google ADK Threads](https://docs.copilotkit.ai/google-adk/threads-import): Import supported Google ADK sessions once; future CopilotKit-mediated runs persist to Intelligence while your durable ADK session service retains native history.
- [Built-in Agent Quickstart](https://docs.copilotkit.ai/quickstart): Build a working agent chat with CopilotKit's built-in agent in a few focused steps.
- [CopilotKit CLI](https://docs.copilotkit.ai/cli): Create an application, choose an agent framework, and connect optional Intelligence services.
- [CopilotKit Architecture](https://docs.copilotkit.ai/concepts/architecture): Understand how the frontend, runtime, agent, and AG-UI event stream fit together.
- [Hook Selection Guide](https://docs.copilotkit.ai/concepts/which-hook): Choose the correct frontend hook for tools, rendering, human review, or custom chat.
- [Open source vs Intelligence](https://docs.copilotkit.ai/concepts/oss-vs-enterprise): Decide which capabilities belong to the open-source stack and which require Intelligence.
- [AG-UI Integration](https://docs.copilotkit.ai/agentic-protocols/ag-ui): Connect a framework-agnostic agent backend to CopilotKit through the open AG-UI protocol.
- [Copilot Runtime](https://docs.copilotkit.ai/backend/copilot-runtime): Configure the server-side layer that authenticates, routes, and connects frontend requests to agents.
- [Bring Your Own Model Runtime](https://docs.copilotkit.ai/backend/custom-agent): Use a custom model router or AI SDK implementation behind CopilotKit's built-in agent interface.
- [Self-Managed Agents](https://docs.copilotkit.ai/backend/self-managed-agents): Connect agents that you host and secure yourself through a compatible AG-UI endpoint.
- [Runtime Deployment](https://docs.copilotkit.ai/runtime-server-adapter): Choose a server adapter for Node.js, Express, Hono, Bun, Deno, or Cloudflare Workers.
- [Built-in Agent Model Selection](https://docs.copilotkit.ai/model-selection): Select and configure the model provider used by CopilotKit's built-in agent.
- [Prebuilt Chat Components](https://docs.copilotkit.ai/prebuilt-components): Choose among embedded chat, sidebar, and popup components for a ready-made interface.
- [Headless Chat UI](https://docs.copilotkit.ai/headless): Build a fully custom chat experience while retaining CopilotKit's agent and rendering primitives.
- [Frontend Tools](https://docs.copilotkit.ai/frontend-tools): Expose browser-side actions that an agent can discover, call, and await.
- [Built-in Agent Server Tools](https://docs.copilotkit.ai/server-tools): Define backend actions that the built-in agent can execute securely on the server.
- [Shared State](https://docs.copilotkit.ai/shared-state): Create a two-way state connection between your application UI and its agent.
- [Thread Lifecycle](https://docs.copilotkit.ai/threads-lifecycle): Understand thread creation, restoration, switching, and framework persistence boundaries.
- [Intelligence Quickstart](https://docs.copilotkit.ai/intelligence/quickstart): Connect an existing CopilotKit application to persistent threads in an Intelligence project.
- [User Memories](https://docs.copilotkit.ai/intelligence/memories): Choose memory scope and integrate long-term recall through React, Angular, REST, or MCP.
- [Product Analytics](https://docs.copilotkit.ai/intelligence/analytics): Understand how people use your agent with project-level usage, thread, and tool-call data.
- [Authentication](https://docs.copilotkit.ai/auth): Forward verified user identity from the frontend through the runtime to the agent.
- [Angular Frontend](https://docs.copilotkit.ai/angular): Start with CopilotKit's first-party, signal-based Angular frontend integration.
- [React Native Frontend](https://docs.copilotkit.ai/frontends/react-native): Start a mobile CopilotKit application with the required Metro and polyfill setup.
- [React SPA Frontend](https://docs.copilotkit.ai/frontends/react-spa): Connect a React single-page application to a separately hosted Copilot Runtime.
- [Vue Frontend](https://docs.copilotkit.ai/frontends/vue): Connect a Vue application to Copilot Runtime with CopilotKit's Vue integration.
