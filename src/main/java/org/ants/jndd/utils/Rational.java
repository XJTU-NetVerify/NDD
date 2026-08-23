package org.ants.jndd.utils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Rational 类：使用 long 存储分子和分母，旨在高性能精确计算。
 */
public class Rational {

    private final long numerator;   // 分子
    private final long denominator; // 分母 (始终为正)
    static int defaultScale = 6; // 默认小数位数，用于 double 转换

    /**
     * Configure the decimal scale used when doubles or overflowing exact
     * fractions are converted to the fixed-point rational representation.
     * Existing Rational values are not changed.
     */
    public static void setDefaultScale(int scale) {
        if (scale < 0 || scale > 18) {
            throw new IllegalArgumentException("scale must be between 0 and 18");
        }
        defaultScale = scale;
    }

    public static int getDefaultScale() {
        return defaultScale;
    }

    // ================== 构造函数 ==================

    /**
     * 主构造函数：构造一个新的有理数对象，并自动约分。
     * @param num 分子
     * @param den 分母
     * @throws ArithmeticException 如果分母为 0 或计算溢出。
     */
    public Rational(long num, long den) {
        if (den == 0) {
            throw new ArithmeticException("Denominator cannot be zero.");
        }

        // 统一符号：确保分母为正，并调整分子的符号
        if (den < 0) {
            num = -num;
            den = -den;
        }

        // 约分
        long gcdVal = gcd(Math.abs(num), den);

        this.numerator = num / gcdVal;
        this.denominator = den / gcdVal;
    }

    /**
     * 构造一个整数形式的有理数 (n/1)
     */
    public Rational(long n) {
        this(n, 1);
    }

    // /**
    //  * 【新增构造函数】从一个 double 值构造一个精确的有理数。
    //  * 该方法解析 double 的 IEEE 754 64 位表示。
    //  * @param value 要转换的 double 值。
    //  * @throws IllegalArgumentException 如果 value 是 NaN 或 Infinity。
    //  * @throws ArithmeticException 如果转换过程中分子或分母超出 long 范围。
    //  */
    // public Rational(double value) {
    //     if (Double.isNaN(value) || Double.isInfinite(value)) {
    //         throw new IllegalArgumentException("Cannot convert NaN or Infinity to a rational number.");
    //     }

    //     if (value == 0.0) {
    //         this.numerator = 0;
    //         this.denominator = 1;
    //         return;
    //     }

    //     long bits = Double.doubleToLongBits(value);

    //     // 1. 提取符号位 (Sign)
    //     int s = (bits >> 63 == 0) ? 1 : -1;

    //     // 2. 提取指数位 (Exponent): 11 位
    //     long exp = (bits >> 52) & 0x7FFL;

    //     // 3. 提取尾数位 (Mantissa/Fraction): 52 位
    //     long man = bits & 0xFFFFFFFFFFFFFL;

    //     // 4. 构造原始分子和分母
    //     // 尾数部分 (包含隐藏的 1): 2^52 + man
    //     long numPart = (1L << 52) | man;

    //     // 5. 调整指数 (E - 52)，其中 E = exp - 1023
    //     int exponent = (int) exp - 1023 - 52;

    //     long finalNum;
    //     long finalDen;

    //     if (exponent > 0) {
    //         // 指数 > 0: 分子 * 2^exponent
    //         // 注意：这里是 long 溢出的主要风险点之一
    //         finalNum = Math.multiplyExact(numPart, 1L << exponent);
    //         finalDen = 1L;
    //     } else if (exponent < 0) {
    //         // 指数 < 0: 分母 * 2^(-exponent)
    //         // 注意：这里是 long 溢出的主要风险点之二 (分母过大)
    //         finalNum = numPart;
    //         finalDen = Math.multiplyExact(1L, 1L << (-exponent));
    //     } else {
    //         // exponent == 0
    //         finalNum = numPart;
    //         finalDen = 1L;
    //     }

    //     // 最终构造新的 Rational，包含符号处理和约分
    //     Rational result = new Rational(s * finalNum, finalDen);

    //     this.numerator = result.numerator;
    //     this.denominator = result.denominator;
    // }




    /**
     * 核心实现：从 double 值构造一个有理数，并四舍五入到指定的小数位数。
     * 采用 BigDecimal 确保精确的四舍五入和转换。
     */
    public Rational(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Cannot convert NaN or Infinity.");
        }

        // 1. 转换为 BigDecimal 并进行四舍五入（核心步骤）
        BigDecimal bd = BigDecimal.valueOf(value);
        // 使用 HALF_UP (四舍五入) 到指定的 scale
        bd = bd.setScale(defaultScale, RoundingMode.HALF_UP);

        // 2. 提取分子和分母
        // 分子 (Unscaled Value)
        BigInteger bigNum = bd.unscaledValue();
        // 分母 (10^scale)
        BigInteger bigDen = BigInteger.TEN.pow(bd.scale());

        // 3. 检查 BigInteger 是否能安全放入 long
        if (bigNum.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ||
            bigNum.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 ||
            bigDen.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
        {
            throw new ArithmeticException(
                "Conversion to rational number with scale " + defaultScale + " caused long overflow. " +
                "Result requires BigInteger storage."
            );
        }

        // 4. 转换为 long 并调用主构造函数进行约分（Sylvan 规范化）
        Rational result = new Rational(bigNum.longValue(), bigDen.longValue());

        this.numerator = result.numerator;
        this.denominator = result.denominator;
    }





    // ================== 核心运算方法 (为保持简洁省略，但与上一个回复中保持一致) ==================

    public Rational add(Rational other) {
        try {
            long g = gcd(this.denominator, other.denominator);
            long term1 = Math.multiplyExact(this.numerator, other.denominator / g);
            long term2 = Math.multiplyExact(other.numerator, this.denominator / g);
            long newNum = Math.addExact(term1, term2);
            long newDen = Math.multiplyExact(this.denominator / g, other.denominator);
            return new Rational(newNum, newDen);
        } catch (ArithmeticException overflow) {
            BigInteger newNum = BigInteger.valueOf(numerator)
                    .multiply(BigInteger.valueOf(other.denominator))
                    .add(BigInteger.valueOf(other.numerator)
                            .multiply(BigInteger.valueOf(denominator)));
            BigInteger newDen = BigInteger.valueOf(denominator)
                    .multiply(BigInteger.valueOf(other.denominator));
            return fromBigFraction(newNum, newDen);
        }
    }

    public Rational multiply(Rational other) {
        try {
            long a = this.numerator; long b = this.denominator;
            long c = other.numerator; long d = other.denominator;
            long gcd_ac = gcd(Math.abs(a), d); a /= gcd_ac; d /= gcd_ac;
            long gcd_bd = gcd(Math.abs(c), b); c /= gcd_bd; b /= gcd_bd;
            long newNum = Math.multiplyExact(a, c);
            long newDen = Math.multiplyExact(b, d);
            return new Rational(newNum, newDen);
        } catch (ArithmeticException overflow) {
            return fromBigFraction(
                    BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.numerator)),
                    BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator)));
        }
    }

    public Rational subtract(Rational other) {
        try {
            long g = gcd(this.denominator, other.denominator);
            long term1 = Math.multiplyExact(this.numerator, other.denominator / g);
            long term2 = Math.multiplyExact(other.numerator, this.denominator / g);
            long newNum = Math.subtractExact(term1, term2);
            long newDen = Math.multiplyExact(this.denominator / g, other.denominator);
            return new Rational(newNum, newDen);
        } catch (ArithmeticException overflow) {
            BigInteger newNum = BigInteger.valueOf(numerator)
                    .multiply(BigInteger.valueOf(other.denominator))
                    .subtract(BigInteger.valueOf(other.numerator)
                            .multiply(BigInteger.valueOf(denominator)));
            BigInteger newDen = BigInteger.valueOf(denominator)
                    .multiply(BigInteger.valueOf(other.denominator));
            return fromBigFraction(newNum, newDen);
        }
    }

    public Rational divide(Rational other) {
        if (other.numerator == 0) {
            throw new ArithmeticException("Division by zero rational number.");
        }
        long numeratorA = this.numerator;
        long denominatorA = this.denominator;
        long numeratorB = other.numerator;
        long denominatorB = other.denominator;
        long numeratorGcd = gcd(Math.abs(numeratorA), Math.abs(numeratorB));
        numeratorA /= numeratorGcd;
        numeratorB /= numeratorGcd;
        long denominatorGcd = gcd(denominatorA, denominatorB);
        denominatorA /= denominatorGcd;
        denominatorB /= denominatorGcd;
        try {
            return new Rational(
                    Math.multiplyExact(numeratorA, denominatorB),
                    Math.multiplyExact(denominatorA, numeratorB));
        } catch (ArithmeticException overflow) {
            return fromBigFraction(
                    BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.denominator)),
                    BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.numerator)));
        }
    }

    private static Rational fromBigFraction(BigInteger num, BigInteger den) {
        if (den.signum() == 0) {
            throw new ArithmeticException("Denominator cannot be zero.");
        }
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger common = num.gcd(den);
        num = num.divide(common);
        den = den.divide(common);
        if (num.bitLength() < 63 && den.bitLength() < 63) {
            return new Rational(num.longValue(), den.longValue());
        }
        BigDecimal rounded = new BigDecimal(num).divide(
                new BigDecimal(den), defaultScale, RoundingMode.HALF_UP);
        BigInteger scaledDen = BigInteger.TEN.pow(defaultScale);
        return new Rational(rounded.unscaledValue().longValueExact(), scaledDen.longValueExact());
    }

    // ================== 高性能 GCD 实现 ==================

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    // ================== 其他方法 ==================

    @Override
    public String toString() {
        if (denominator == 1) return String.valueOf(numerator);
        return numerator + "/" + denominator;
    }

    /**
     * 实现 hashCode 方法，确保相等的 Rational 对象具有相同的哈希码。
     * 策略：使用 java.util.Objects.hash() 组合分子和分母的哈希值。
     * 由于构造函数保证了分子和分母是规范化的（最简、分母为正），
     * 只要分子和分母相同，哈希码就一定相同。
     */
    @Override
    public int hashCode() {
        return 31 * Long.hashCode(numerator) + Long.hashCode(denominator);
    }

    /**
     * 实现 equals 方法，确保两个 Rational 对象在数学上相等。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        // 强制类型转换
        Rational rational = (Rational) o;

        // 因为构造函数保证了分数是最简形式（规范化），
        // 两个 Rational 相等，当且仅当它们的规范化后的分子和分母分别相等。
        return numerator == rational.numerator && denominator == rational.denominator;
    }

    public double doubleValue() {
        return (double) numerator / denominator;
    }

    public long numerator() {
        return numerator;
    }

    public long denominator() {
        return denominator;
    }
}
