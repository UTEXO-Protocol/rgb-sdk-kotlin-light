package com.utexo.rgb.sdk

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Exact Bitcoin amount. The maximum is Bitcoin's 21 million BTC supply. */
@JvmInline
public value class Satoshis private constructor(public val value: Long) : Comparable<Satoshis> {
    override fun compareTo(other: Satoshis): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    public companion object {
        public val ZERO: Satoshis = Satoshis(0)
        public const val MAX_VALUE: Long = 2_100_000_000_000_000L

        public fun of(value: Long): Satoshis {
            if (value !in 0..MAX_VALUE) {
                throw SdkException.InvalidArgument(
                    field = "satoshis",
                    reason = "must be between 0 and $MAX_VALUE",
                )
            }
            return Satoshis(value)
        }

        internal fun positive(field: String, value: Satoshis): Satoshis {
            if (value.value == 0L) {
                throw SdkException.InvalidArgument(field, "must be greater than zero")
            }
            return value
        }
    }
}

/**
 * Exact fee rate stored as milli-satoshis per virtual byte.
 *
 * The integer representation means the RN default of 1.5 sat/vB is exactly
 * 1500 msat/vB; floating-point values never cross the signing boundary.
 */
@JvmInline
public value class FeeRate private constructor(public val milliSatsPerVbyte: Long) : Comparable<FeeRate> {
    override fun compareTo(other: FeeRate): Int =
        milliSatsPerVbyte.compareTo(other.milliSatsPerVbyte)

    override fun toString(): String =
        BigDecimal.valueOf(milliSatsPerVbyte, 3).stripTrailingZeros().toPlainString() + " sat/vB"

    public companion object {
        public val DEFAULT: FeeRate = FeeRate(1_500)
        /** Absolute representable ceiling; wallet signing policy is intentionally stricter. */
        public val MAX_SUPPORTED: FeeRate = FeeRate(10_000_000)

        public fun milliSatsPerVbyte(value: Long): FeeRate {
            if (value <= 0L || value > MAX_SUPPORTED.milliSatsPerVbyte) {
                throw SdkException.InvalidArgument(
                    field = "feeRate",
                    reason = "must be between 1 and ${MAX_SUPPORTED.milliSatsPerVbyte} msat/vB",
                )
            }
            return FeeRate(value)
        }

        public fun satPerVbyte(value: String): FeeRate {
            val parsed = try {
                BigDecimal(value)
            } catch (_: NumberFormatException) {
                throw SdkException.InvalidArgument("feeRate", "must be a decimal number")
            }
            return fromDecimal(parsed)
        }

        public fun satPerVbyte(value: Double): FeeRate {
            if (!value.isFinite() || value <= 0.0) {
                throw SdkException.InvalidArgument("feeRate", "must be finite and greater than zero")
            }
            return fromDecimal(BigDecimal.valueOf(value))
        }

        private fun fromDecimal(value: BigDecimal): FeeRate {
            if (value.signum() <= 0) {
                throw SdkException.InvalidArgument("feeRate", "must be greater than zero")
            }
            val milliSats = try {
                value
                    .movePointRight(3)
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                throw SdkException.InvalidArgument(
                    "feeRate",
                    "must have at most three decimal places and fit the supported range",
                )
            }
            return milliSatsPerVbyte(milliSats)
        }
    }
}

/** Unsigned RGB base-unit amount represented without JVM signed-long overflow. */
@JvmInline
public value class AssetAmount private constructor(public val decimal: String) : Comparable<AssetAmount> {
    override fun compareTo(other: AssetAmount): Int =
        BigInteger(decimal).compareTo(BigInteger(other.decimal))

    override fun toString(): String = decimal

    public companion object {
        public val ZERO: AssetAmount = AssetAmount("0")
        public const val MAX_VALUE: String = "18446744073709551615"
        private val max: BigInteger = BigInteger(MAX_VALUE)

        public fun of(value: Long): AssetAmount {
            if (value < 0L) {
                throw SdkException.InvalidArgument("assetAmount", "must not be negative")
            }
            return AssetAmount(value.toString())
        }

        public fun parse(value: String): AssetAmount {
            if (value.isEmpty() || value.any { !it.isDigit() }) {
                throw SdkException.InvalidArgument(
                    "assetAmount",
                    "must be an unsigned base-10 integer",
                )
            }
            val canonical = value.trimStart('0').ifEmpty { "0" }
            val parsed = BigInteger(canonical)
            if (parsed > max) {
                throw SdkException.InvalidArgument("assetAmount", "exceeds unsigned 64-bit range")
            }
            return AssetAmount(canonical)
        }

        internal fun positive(field: String, value: AssetAmount): AssetAmount {
            if (value == ZERO) {
                throw SdkException.InvalidArgument(field, "must be greater than zero")
            }
            return value
        }
    }
}

@JvmInline
public value class BlockCount private constructor(public val value: Int) {
    public companion object {
        public fun of(value: Int): BlockCount {
            if (value < 0) throw SdkException.InvalidArgument("blockCount", "must not be negative")
            return BlockCount(value)
        }
    }
}

@JvmInline
public value class DurationSeconds private constructor(public val value: Long) {
    public companion object {
        public fun of(value: Long): DurationSeconds {
            if (value <= 0L) {
                throw SdkException.InvalidArgument("durationSeconds", "must be greater than zero")
            }
            return DurationSeconds(value)
        }
    }
}

@JvmInline
public value class UnixSeconds private constructor(public val value: Long) {
    public companion object {
        public fun of(value: Long): UnixSeconds {
            if (value < 0L) throw SdkException.InvalidArgument("unixSeconds", "must not be negative")
            return UnixSeconds(value)
        }
    }
}
