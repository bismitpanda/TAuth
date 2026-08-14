package com.panda.tauth.totp

import com.panda.tauth.Outcome
import com.panda.tauth.flatMap
import com.panda.tauth.map
import com.panda.tauth.vault.VaultError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OtpType {
    @SerialName("totp")
    TOTP,

    @SerialName("hotp")
    HOTP,
    ;

    val uriAuthority: String get() = name.lowercase()

    companion object {
        fun parse(value: String): OtpType? = parseIgnoreCase<OtpType>(value)
    }
}

// Google Authenticator Key Uri Format.
data class OtpAuthUri(
    val type: OtpType,
    val accountName: String,
    val secret: String,
    val issuer: String? = null,
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: Int = OtpCore.DIGITS_DEFAULT,
    val period: Int? = Totp.PERIOD_DEFAULT,
    val counter: ULong? = null,
) {
    init {
        require(accountName.isNotEmpty()) { "accountName must not be empty" }
        // Parsing decodes before it splits, so %3A separates too and a colon here would re-parse as
        // a different account name.
        require(LABEL_SEPARATOR !in accountName) { "accountName must not contain a colon" }
        require(issuer == null || issuer.isNotEmpty()) { "issuer must be absent rather than empty" }
        // A name with no UTF-8 encoding cannot be percent-encoded, and build() is the only thing
        // that would find out.
        require(accountName.isWellFormed()) { "accountName must be well-formed text" }
        require(issuer == null || issuer.isWellFormed()) { "issuer must be well-formed text" }
        // The rule the parser applies, so every URI this constructor accepts re-parses. The message
        // states the rule rather than the value, which is the secret.
        require(Base32.validateSecret(secret) == null) { "secret must be base32 that decodes to a key" }
        require(digits in OtpCore.DIGITS_MIN..OtpCore.DIGITS_MAX) {
            "digits must be ${OtpCore.DIGITS_MIN}..${OtpCore.DIGITS_MAX}"
        }
        when (type) {
            OtpType.TOTP -> {
                require(period != null && period >= Totp.PERIOD_MIN) {
                    "period must be at least ${Totp.PERIOD_MIN}"
                }
                require(counter == null) { "a totp URI carries no counter" }
            }

            OtpType.HOTP -> {
                require(period == null) { "a hotp URI carries no period" }
                require(counter != null) { "a hotp URI requires a counter" }
            }
        }
    }

    override fun toString(): String =
        "OtpAuthUri(type=$type, issuer=$issuer, accountName=$accountName, algorithm=$algorithm, " +
            "digits=$digits, period=$period, counter=$counter, secret=<redacted>)"

    fun build(): String {
        // The prefix is carried only when it round-trips: a colon in the issuer or a leading space
        // in the account name would re-parse as something else, so the parameter carries it alone.
        val prefixable = issuer != null && LABEL_SEPARATOR !in issuer && !accountName.startsWith(' ')
        val label = if (prefixable) {
            "${percentEncode(issuer)}$LABEL_SEPARATOR${percentEncode(accountName)}"
        } else {
            percentEncode(accountName)
        }
        val params = buildList {
            add("$PARAM_SECRET=${percentEncode(secret)}")
            if (issuer != null) add("$PARAM_ISSUER=${percentEncode(issuer)}")
            if (algorithm != HashAlgorithm.SHA1) add("$PARAM_ALGORITHM=${algorithm.name}")
            if (digits != OtpCore.DIGITS_DEFAULT) add("$PARAM_DIGITS=$digits")
            when (type) {
                OtpType.TOTP -> if (period != Totp.PERIOD_DEFAULT) add("$PARAM_PERIOD=$period")
                OtpType.HOTP -> add("$PARAM_COUNTER=$counter")
            }
        }
        return "$SCHEME${type.uriAuthority}/$label?${params.joinToString(PARAM_SEPARATOR)}"
    }

    companion object {
        private const val SCHEME = "otpauth://"

        // Internal because a stored entry is held to the same rule as a parsed one, and one colon
        // defines it for both.
        internal const val LABEL_SEPARATOR = ':'

        private const val PARAM_SEPARATOR = "&"
        private const val PARAM_SECRET = "secret"
        private const val PARAM_ISSUER = "issuer"
        private const val PARAM_ALGORITHM = "algorithm"
        private const val PARAM_DIGITS = "digits"
        private const val PARAM_PERIOD = "period"
        private const val PARAM_COUNTER = "counter"

        // What a paste or a line wrap adds. Char.isWhitespace is wider and would shed characters a
        // label is entitled to hold.
        private const val WRAPPING_WHITESPACE = " \t\r\n"

        fun parse(input: String): Outcome<OtpAuthUri, VaultError> {
            // A paste carries the wrapping of the window it came from, and only these four
            // characters are shed, only at the ends. The single value a shed character can come
            // out of is the last query parameter's, with no fragment behind it: a trailing space
            // is dropped there, while the same space one character earlier makes the URI malformed.
            val trimmed = input.trim { it in WRAPPING_WHITESPACE }
            if (!trimmed.startsWith(SCHEME, ignoreCase = true)) {
                return Outcome.Failure(VaultError.MalformedUri("not an otpauth URI"))
            }
            val body = trimmed.substring(SCHEME.length).substringBefore('#')
            val path = body.substringBefore('?')
            val type = OtpType.parse(path.substringBefore('/'))
                ?: return Outcome.Failure(VaultError.MalformedUri("unknown OTP type"))
            return parseQuery(body.substringAfter('?', "")).flatMap { params ->
                parseLabel(path.substringAfter('/', "")).flatMap { label -> assemble(type, label, params) }
            }
        }

        private fun parseLabel(rawLabel: String): Outcome<Label, VaultError> {
            val label = percentDecode(rawLabel)
                ?: return Outcome.Failure(VaultError.MalformedUri("malformed percent-encoding in the label"))
            // Decoding precedes the split, so %3A separates just as a literal colon does.
            val separator = label.indexOf(LABEL_SEPARATOR)
            // Not trimmed: the ABNF is `issuer (":" / "%3A") *"%20" accountname`, so padding sits
            // after the separator and a space before it belongs to the issuer.
            val issuer = if (separator < 0) null else label.take(separator)
            // Only literal spaces are absorbed, and only after the separator. trimStart() would take
            // every Unicode whitespace character, silently renaming an account.
            val accountName =
                if (separator < 0) label else label.substring(separator + 1).trimStart(' ')
            if (accountName.isEmpty()) {
                return Outcome.Failure(VaultError.MalformedUri("missing account name"))
            }
            if (LABEL_SEPARATOR in accountName) {
                return Outcome.Failure(VaultError.MalformedUri("label carries more than one colon"))
            }
            if (issuer != null && issuer.isEmpty()) {
                return Outcome.Failure(VaultError.MalformedUri("label begins with the separator"))
            }
            return Outcome.Success(Label(issuer, accountName))
        }

        private fun parseQuery(query: String): Outcome<Map<String, String>, VaultError> {
            // A producer writes %20 into a value, so raw whitespace here came from a wrapped paste,
            // and a value would keep it faithfully and store an issuer nobody typed. The label ends
            // at the '?' and a space in it is part of a name, so it keeps its own.
            if (query.any { it in WRAPPING_WHITESPACE }) {
                return Outcome.Failure(VaultError.MalformedUri("the query holds whitespace"))
            }
            val params = mutableMapOf<String, String>()
            for (pair in query.split(PARAM_SEPARATOR)) {
                if (pair.isEmpty()) continue
                val name = percentDecode(pair.substringBefore('=')) ?: return malformedParameter()
                val value = percentDecode(pair.substringAfter('=', "")) ?: return malformedParameter()
                if (params.put(name.lowercase(), value) != null) {
                    return Outcome.Failure(VaultError.MalformedUri("a parameter appears more than once"))
                }
            }
            return Outcome.Success(params)
        }

        private fun malformedParameter(): Outcome<Map<String, String>, VaultError> =
            Outcome.Failure(VaultError.MalformedUri("malformed percent-encoding in a parameter"))

        private fun assemble(
            type: OtpType,
            label: Label,
            params: Map<String, String>,
        ): Outcome<OtpAuthUri, VaultError> = parseSecret(params).flatMap { secret ->
            parseAlgorithm(params).flatMap { algorithm ->
                parseDigits(params).flatMap { digits ->
                    parseMovingFactor(type, params).map { factor ->
                        fromParts(type, label, params, secret, algorithm, digits, factor)
                    }
                }
            }
        }

        private fun fromParts(
            type: OtpType,
            label: Label,
            params: Map<String, String>,
            secret: String,
            algorithm: HashAlgorithm,
            digits: Int,
            factor: MovingFactor,
        ): OtpAuthUri {
            val issuer = params[PARAM_ISSUER]?.ifEmpty { null } ?: label.issuer
            return OtpAuthUri(
                type = type,
                accountName = label.accountName,
                secret = secret,
                issuer = issuer,
                algorithm = algorithm,
                digits = digits,
                period = factor.period,
                counter = factor.counter,
            )
        }

        private fun parseSecret(params: Map<String, String>): Outcome<String, VaultError> {
            val secret = params[PARAM_SECRET]
                ?: return Outcome.Failure(VaultError.InvalidSecret("missing secret"))
            val error = Base32.validateSecret(secret)
            return if (error == null) Outcome.Success(secret) else Outcome.Failure(error)
        }

        private fun parseAlgorithm(params: Map<String, String>): Outcome<HashAlgorithm, VaultError> {
            val raw = params[PARAM_ALGORITHM] ?: return Outcome.Success(HashAlgorithm.SHA1)
            val algorithm = HashAlgorithm.parse(raw)
                ?: return Outcome.Failure(VaultError.MalformedUri("unknown algorithm"))
            return Outcome.Success(algorithm)
        }

        private fun parseDigits(params: Map<String, String>): Outcome<Int, VaultError> {
            val raw = params[PARAM_DIGITS] ?: return Outcome.Success(OtpCore.DIGITS_DEFAULT)
            val digits = raw.toAsciiIntOrNull()?.takeIf { it in OtpCore.DIGITS_MIN..OtpCore.DIGITS_MAX }
                ?: return Outcome.Failure(
                    VaultError.MalformedUri("digits must be ${OtpCore.DIGITS_MIN}..${OtpCore.DIGITS_MAX}"),
                )
            return Outcome.Success(digits)
        }

        private fun parseMovingFactor(type: OtpType, params: Map<String, String>): Outcome<MovingFactor, VaultError> =
            when (type) {
                OtpType.TOTP -> parseTotpFactor(params)
                OtpType.HOTP -> parseHotpFactor(params)
            }

        private fun parseTotpFactor(params: Map<String, String>): Outcome<MovingFactor, VaultError> {
            if (PARAM_COUNTER in params) {
                return Outcome.Failure(VaultError.MalformedUri("counter is not valid on a totp URI"))
            }
            val raw = params[PARAM_PERIOD] ?: return Outcome.Success(MovingFactor(Totp.PERIOD_DEFAULT, null))
            val period = raw.toAsciiIntOrNull()?.takeIf { it >= Totp.PERIOD_MIN }
                ?: return Outcome.Failure(
                    VaultError.MalformedUri("period must be at least ${Totp.PERIOD_MIN}"),
                )
            return Outcome.Success(MovingFactor(period, null))
        }

        private fun parseHotpFactor(params: Map<String, String>): Outcome<MovingFactor, VaultError> {
            // The counter alone has no default: a wrong starting position yields codes no server
            // accepts.
            val raw = params[PARAM_COUNTER]
                ?: return Outcome.Failure(VaultError.MalformedUri("a hotp URI requires a counter"))
            val counter = raw.toAsciiULongOrNull()
                ?: return Outcome.Failure(VaultError.MalformedUri("counter must be an unsigned 64-bit integer"))
            return Outcome.Success(MovingFactor(null, counter))
        }
    }
}

private data class Label(val issuer: String?, val accountName: String)

private data class MovingFactor(val period: Int?, val counter: ULong?)
