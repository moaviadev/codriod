package dev.moavia.codriod.agent

/** Wire format a provider speaks. Most providers expose an OpenAI-compatible endpoint. */
enum class ApiStyle { OpenAI, Anthropic }

enum class Tier(val label: String) { Free("Free"), FreeTier("Free tier"), Pro("Pro"), Local("Local") }

data class ProviderSpec(
    val id: String,
    val name: String,
    val style: ApiStyle,
    val baseUrl: String,
    val tier: Tier,
    val keyUrl: String? = null,
    val needsKey: Boolean = true,
    /** Overrides `{baseUrl}/models` when the provider lists models elsewhere. */
    val modelsUrl: String? = null,
    val supportsTools: Boolean = true,
    val note: String = "",
    /** Shown before the user fetches the live list, and used when the provider has no list endpoint. */
    val fallbackModels: List<String> = emptyList(),
    val editableUrl: Boolean = false
)

object Providers {
    const val CUSTOM = "custom"

    val all: List<ProviderSpec> = listOf(
        // Free or generous free tiers
        ProviderSpec("gemini", "Google Gemini", ApiStyle.OpenAI,
            "https://generativelanguage.googleapis.com/v1beta/openai", Tier.FreeTier,
            keyUrl = "https://aistudio.google.com/apikey",
            note = "Free key from Google AI Studio.",
            fallbackModels = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite")),
        ProviderSpec("groq", "Groq", ApiStyle.OpenAI, "https://api.groq.com/openai/v1", Tier.Free,
            keyUrl = "https://console.groq.com/keys",
            note = "Very fast open models with a free rate-limited tier.",
            fallbackModels = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "qwen/qwen3-32b")),
        ProviderSpec("openrouter", "OpenRouter", ApiStyle.OpenAI, "https://openrouter.ai/api/v1", Tier.FreeTier,
            keyUrl = "https://openrouter.ai/keys",
            note = "Hundreds of models from one key. Models marked free cost nothing.",
            fallbackModels = listOf("openrouter/auto")),
        ProviderSpec("cerebras", "Cerebras", ApiStyle.OpenAI, "https://api.cerebras.ai/v1", Tier.Free,
            keyUrl = "https://cloud.cerebras.ai/",
            fallbackModels = listOf("llama-3.3-70b", "gpt-oss-120b", "qwen-3-32b")),
        ProviderSpec("mistral", "Mistral AI", ApiStyle.OpenAI, "https://api.mistral.ai/v1", Tier.FreeTier,
            keyUrl = "https://console.mistral.ai/api-keys",
            fallbackModels = listOf("mistral-large-latest", "codestral-latest", "mistral-small-latest")),
        ProviderSpec("github", "GitHub Models", ApiStyle.OpenAI, "https://models.github.ai/inference", Tier.Free,
            keyUrl = "https://github.com/settings/tokens",
            modelsUrl = "https://models.github.ai/catalog/models",
            note = "Use a GitHub personal access token with the models permission.",
            fallbackModels = listOf("openai/gpt-4.1", "openai/gpt-4.1-mini", "meta/Llama-3.3-70B-Instruct")),
        ProviderSpec("huggingface", "Hugging Face", ApiStyle.OpenAI, "https://router.huggingface.co/v1", Tier.FreeTier,
            keyUrl = "https://huggingface.co/settings/tokens",
            fallbackModels = listOf("Qwen/Qwen3-Coder-480B-A35B-Instruct", "meta-llama/Llama-3.3-70B-Instruct")),
        ProviderSpec("nvidia", "NVIDIA NIM", ApiStyle.OpenAI, "https://integrate.api.nvidia.com/v1", Tier.FreeTier,
            keyUrl = "https://build.nvidia.com/",
            fallbackModels = listOf("meta/llama-3.3-70b-instruct", "qwen/qwen3-coder-480b-a35b-instruct")),
        ProviderSpec("sambanova", "SambaNova", ApiStyle.OpenAI, "https://api.sambanova.ai/v1", Tier.FreeTier,
            keyUrl = "https://cloud.sambanova.ai/apis",
            fallbackModels = listOf("Meta-Llama-3.3-70B-Instruct", "DeepSeek-V3.1")),
        ProviderSpec("cohere", "Cohere", ApiStyle.OpenAI, "https://api.cohere.ai/compatibility/v1", Tier.FreeTier,
            keyUrl = "https://dashboard.cohere.com/api-keys",
            modelsUrl = "https://api.cohere.com/v1/models?endpoint=chat",
            fallbackModels = listOf("command-a-03-2025", "command-r-plus")),

        // Paid
        ProviderSpec("openai", "OpenAI", ApiStyle.OpenAI, "https://api.openai.com/v1", Tier.Pro,
            keyUrl = "https://platform.openai.com/api-keys",
            fallbackModels = listOf("gpt-5", "gpt-5-mini", "gpt-4.1", "o4-mini")),
        ProviderSpec("anthropic", "Anthropic Claude", ApiStyle.Anthropic, "https://api.anthropic.com/v1", Tier.Pro,
            keyUrl = "https://console.anthropic.com/settings/keys",
            fallbackModels = listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5")),
        ProviderSpec("deepseek", "DeepSeek", ApiStyle.OpenAI, "https://api.deepseek.com/v1", Tier.Pro,
            keyUrl = "https://platform.deepseek.com/api_keys",
            fallbackModels = listOf("deepseek-chat", "deepseek-reasoner")),
        ProviderSpec("xai", "xAI Grok", ApiStyle.OpenAI, "https://api.x.ai/v1", Tier.Pro,
            keyUrl = "https://console.x.ai/",
            fallbackModels = listOf("grok-4", "grok-code-fast-1", "grok-3-mini")),
        ProviderSpec("together", "Together AI", ApiStyle.OpenAI, "https://api.together.xyz/v1", Tier.Pro,
            keyUrl = "https://api.together.xyz/settings/api-keys",
            fallbackModels = listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen3-Coder-480B-A35B-Instruct-FP8")),
        ProviderSpec("fireworks", "Fireworks AI", ApiStyle.OpenAI, "https://api.fireworks.ai/inference/v1", Tier.Pro,
            keyUrl = "https://fireworks.ai/account/api-keys",
            fallbackModels = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct")),
        ProviderSpec("perplexity", "Perplexity", ApiStyle.OpenAI, "https://api.perplexity.ai", Tier.Pro,
            keyUrl = "https://www.perplexity.ai/settings/api",
            supportsTools = false,
            note = "Web-search answers. Chat only: Perplexity does not support agent tools.",
            fallbackModels = listOf("sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research")),

        // Local servers on this phone or reachable on localhost
        ProviderSpec("ollama", "Ollama", ApiStyle.OpenAI, "http://localhost:11434/v1", Tier.Local,
            needsKey = false, editableUrl = true,
            note = "A local Ollama server. No key needed."),
        ProviderSpec("lmstudio", "LM Studio", ApiStyle.OpenAI, "http://localhost:1234/v1", Tier.Local,
            needsKey = false, editableUrl = true),

        ProviderSpec(CUSTOM, "Custom (OpenAI-compatible)", ApiStyle.OpenAI, "", Tier.Local,
            needsKey = false, editableUrl = true,
            note = "Any server with OpenAI-style /models and /chat/completions endpoints.")
    )

    fun byId(id: String?): ProviderSpec? = all.firstOrNull { it.id == id }
}
