package io.github.tabilzad.ktor

import io.github.tabilzad.ktor.k1.visitors.KtorDescriptionBag
import io.github.tabilzad.ktor.k2.ClassIds.TRANSIENT_ANNOTATION_FQ
import io.github.tabilzad.ktor.k2.visitors.StringArrayLiteralVisitor
import io.github.tabilzad.ktor.k2.visitors.StringResolutionVisitor
import io.github.tabilzad.ktor.output.OpenApiSpec
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.result
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.stubs.elements.KtModifierListElementType
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyPublicApi
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.util.PrivateForInline
import org.jetbrains.kotlin.util.getChildren
import java.io.OutputStream

fun Boolean.byFeatureFlag(flag: Boolean): Boolean = if (flag) {
    this
} else {
    true
}

@Deprecated("K1 only", replaceWith = ReplaceWith("ConeKotlinType.getMembers"))
internal fun MemberScope.forEachVariable(configuration: PluginConfiguration, predicate: (PropertyDescriptor) -> Unit) {
    getDescriptorsFiltered(DescriptorKindFilter.VARIABLES)
        .asSequence()
        .map { it.original }
        .filterIsInstance<PropertyDescriptor>()
        .filter {
            it.isEffectivelyPublicApi.byFeatureFlag(configuration.hidePrivateFields)
        }
        .filter {
            (!it.annotations.hasAnnotation(TRANSIENT_ANNOTATION_FQ)).byFeatureFlag(configuration.hideTransients)
        }
        .filter {
            (!(it.backingField?.annotations?.hasAnnotation(TRANSIENT_ANNOTATION_FQ) == true
                    || it.delegateField?.annotations?.hasAnnotation(TRANSIENT_ANNOTATION_FQ) == true
                    || it.setter?.annotations?.hasAnnotation(TRANSIENT_ANNOTATION_FQ) == true
                    || it.getter?.annotations?.hasAnnotation(TRANSIENT_ANNOTATION_FQ) == true
                    )

                    ).byFeatureFlag(
                    configuration.hideTransients
                )
        }
        .toList().forEach { predicate(it) }
}

internal val Iterable<OpenApiSpec.ObjectType>.names get() = mapNotNull { it.fqName }

fun String?.addLeadingSlash() = when {
    this == null -> null
    else -> if (this.startsWith("/")) this else "/$this"
}

internal fun reduce(e: DocRoute): List<KtorRouteSpec> = e.children.flatMap { child ->
    when (child) {
        is DocRoute -> {
            reduce(
                child.copy(
                    path = e.path + child.path.addLeadingSlash(),
                    tags = e.tags merge child.tags
                )
            )
        }

        is EndPoint -> {
            listOf(
                KtorRouteSpec(
                    path = e.path + (child.path.addLeadingSlash() ?: ""),
                    method = child.method,
                    body = child.body ?: OpenApiSpec.ObjectType("object"),
                    summary = child.summary,
                    description = child.description,
                    parameters = child.parameters?.toList(),
                    responses = child.responses,
                    operationId = child.operationId,
                    tags = e.tags merge child.tags
                )
            )
        }
    }
}

internal fun List<KtorRouteSpec>.cleanPaths() = map {
    it.copy(
        path = it.path
            .replace("//", "/")
            .replace("?", "")
    )
}

internal fun List<KtorRouteSpec>.convertToSpec(): Map<String, Map<String, OpenApiSpec.Path>> = groupBy { it ->
    it.path
}.mapValues { (_, value) ->
    value.associate {
        it.method to OpenApiSpec.Path(
            summary = it.summary,
            description = it.description,
            operationId = it.operationId,
            tags = it.tags?.toList()?.sorted(),
            parameters = mapPathParams(it) merge mapQueryParams(it) merge mapHeaderParams(it),
            requestBody = addPostBody(it),
            responses = it.responses
        )
    }
}

infix fun <T> List<T>?.merge(params: List<T>?): List<T>? = this?.plus(params ?: emptyList()) ?: params

infix fun <T> Set<T>?.merge(params: Set<T>?): Set<T>? = this?.plus(params ?: emptyList()) ?: params

private fun mapPathParams(spec: KtorRouteSpec): List<OpenApiSpec.Parameter>? {
    val params = "\\{([^}]*)}".toRegex().findAll(spec.path).toList()
    return if (params.isNotEmpty()) {
        params.mapNotNull {
            val pathParamName = it.groups[1]?.value
            if (pathParamName.isNullOrBlank() || spec.parameters
                    ?.filterIsInstance<PathParamSpec>()
                    ?.any { it.name == pathParamName } == true
            ) {
                spec.parameters?.find { it.name == pathParamName }?.let {
                    OpenApiSpec.Parameter(
                        name = it.name,
                        `in` = "path",
                        required = pathParamName?.contains("?") != true,
                        schema = OpenApiSpec.SchemaType("string"),
                        description = it.description
                    )
                }
            } else {
                OpenApiSpec.Parameter(
                    name = pathParamName.replace("?", ""),
                    `in` = "path",
                    required = !pathParamName.contains("?"),
                    schema = OpenApiSpec.SchemaType("string")
                )
            }
        }
    } else {
        null
    }
}

private fun mapQueryParams(it: KtorRouteSpec): List<OpenApiSpec.Parameter>? {
    return it.parameters?.filterIsInstance<QueryParamSpec>()?.map {
        OpenApiSpec.Parameter(
            name = it.name,
            `in` = "query",
            required = it.isRequired,
            schema = OpenApiSpec.SchemaType("string"),
            description = it.description
        )
    }
}

private fun mapHeaderParams(it: KtorRouteSpec): List<OpenApiSpec.Parameter>? {
    return it.parameters?.filterIsInstance<HeaderParamSpec>()?.map {
        OpenApiSpec.Parameter(
            name = it.name,
            `in` = "header",
            required = it.isRequired,
            schema = OpenApiSpec.SchemaType("string"),
            description = it.description
        )
    }
}


private fun addPostBody(it: KtorRouteSpec): OpenApiSpec.RequestBody? {
    return if (it.method != "get" && it.body.contentBodyRef != null) {
        OpenApiSpec.RequestBody(
            required = true,
            content = mapOf(
                ContentType.APPLICATION_JSON to mapOf(
                    "schema" to OpenApiSpec.SchemaType(
                        `$ref` = "${it.body.contentBodyRef}"
                    )
                )
            )
        )
    } else if (it.method != "get" && it.body.isPrimitive()) {
        OpenApiSpec.RequestBody(
            required = true,
            content = mapOf(
                ContentType.TEXT_PLAIN to mapOf(
                    "schema" to OpenApiSpec.SchemaType(
                        type = "${it.body.type}"
                    )
                )
            )
        )
    } else {
        null
    }
}

internal fun FirRegularClassSymbol.getKDocComments(configuration: PluginConfiguration): String? {
    if (!configuration.useKDocsForDescriptions) return null

    fun String.sanitizeKDoc(): String {
        return removePrefix("/**")
            .removeSuffix("*/")
            .lineSequence()
            .joinToString("\n") { line ->
                line.trim().removePrefix("*")
            }
            .trim()
    }

    val c = source?.treeStructure?.let {
        val children = source?.lighterASTNode?.getChildren(it)
        val directComment = children
            ?.firstOrNull { it.tokenType == KtTokens.DOC_COMMENT || it.tokenType == KDocTokens.KDOC }

        if (directComment == null) {

            children
                ?.firstOrNull { it.tokenType is KtModifierListElementType<*> }
                ?.getChildren(it)
                ?.firstOrNull { it.tokenType == KtTokens.DOC_COMMENT }

        } else {
            directComment
        }
    }?.toString()
        ?.sanitizeKDoc()

    return c
}

internal fun FirDeclaration.getKDocComments(configuration: PluginConfiguration): String? {

    if (!configuration.useKDocsForDescriptions) return null

    fun String.sanitizeKDoc(): String {
        return removePrefix("/**")
            .removeSuffix("*/")
            .lineSequence()
            .joinToString("\n") { line ->
                line.trim().removePrefix("*")
            }
            .trim()
    }

    val c = source?.treeStructure?.let {
        val children = source?.lighterASTNode?.getChildren(it)
        val directComment = children
            ?.firstOrNull { it.tokenType == KtTokens.DOC_COMMENT || it.tokenType == KDocTokens.KDOC }

        if (directComment == null) {

            children
                ?.firstOrNull { it.tokenType is KtModifierListElementType<*> }
                ?.getChildren(it)
                ?.firstOrNull { it.tokenType == KtTokens.DOC_COMMENT }

        } else {
            directComment
        }
    }?.toString()
        ?.sanitizeKDoc()

    return c
}


private fun OpenApiSpec.ObjectType.isPrimitive() = listOf("string", "number", "integer").contains(type)

internal fun CompilerConfiguration?.buildPluginConfiguration(): PluginConfiguration = PluginConfiguration.createDefault(
    isEnabled = this?.get(SwaggerConfigurationKeys.ARG_ENABLED),
    format = this?.get(SwaggerConfigurationKeys.ARG_FORMAT),
    title = this?.get(SwaggerConfigurationKeys.ARG_TITLE),
    description = this?.get(SwaggerConfigurationKeys.ARG_DESCR),
    version = this?.get(SwaggerConfigurationKeys.ARG_VER),
    filePath = this?.get(SwaggerConfigurationKeys.ARG_PATH),
    requestBody = this?.get(SwaggerConfigurationKeys.ARG_REQUEST_FEATURE),
    hideTransients = this?.get(SwaggerConfigurationKeys.ARG_HIDE_TRANSIENTS),
    hidePrivateFields = this?.get(SwaggerConfigurationKeys.ARG_HIDE_PRIVATE),
    deriveFieldRequirementFromTypeNullability = this?.get(SwaggerConfigurationKeys.ARG_DERIVE_PROP_REQ),
    servers = this?.get(SwaggerConfigurationKeys.ARG_SERVERS) ?: emptyList()
)

operator fun OutputStream.plusAssign(str: String) {
    this.write(str.toByteArray())
}

@OptIn(PrivateForInline::class)
internal fun FirAnnotation.extractDescription(session: FirSession): KtorDescriptionBag {
    val resolved = FirExpressionEvaluator.evaluateAnnotationArguments(this, session)
    val summary = resolved?.entries?.find { it.key.asString() == "summary" }?.value?.result
    val descr = resolved?.entries?.find { it.key.asString() == "description" }?.value?.result
    val required = resolved?.entries?.find { it.key.asString() == "required" }?.value?.result
    val operationId = resolved?.entries?.find { it.key.asString() == "operationId" }?.value?.result
    val tags = resolved?.entries?.find { it.key.asString() == "tags" }?.value?.result
    val explicitType = resolved?.entries?.find { it.key.asString() == "explicitType" }?.value?.result
    val format = resolved?.entries?.find { it.key.asString() == "format" }?.value?.result

    return KtorDescriptionBag(
        summary = summary?.accept(StringResolutionVisitor(session), ""),
        description = descr?.accept(StringResolutionVisitor(session), ""),
        operationId = operationId?.accept(StringResolutionVisitor(session), ""),
        tags = tags?.accept(StringArrayLiteralVisitor(), emptyList())?.toSet(),
        isRequired = required?.accept(StringResolutionVisitor(session), "")?.toBooleanStrictOrNull(),
        explicitType = explicitType?.accept(StringResolutionVisitor(session), ""),
        format = format?.accept(StringResolutionVisitor(session), "")
    )
}
