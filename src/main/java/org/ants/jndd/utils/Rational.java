package org.ants.jndd.utils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;


public class Rational {

    private final long numerator;   
    private final long denominator; 
    static int defaultScale = 6; 


    public Rational(long num, long den) {
        if (den == 0) {
            throw new ArithmeticException("Denominator cannot be zero.");
        }

        if (den < 0) {
            num = -num; 
            den = -den; 
        }

        long gcdVal = gcd(Math.abs(num), den);

        this.numerator = num / gcdVal;
        this.denominator = den / gcdVal;
    }

    public Rational(long n) {
        this(n, 1);
    }

    public Rational(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Cannot convert NaN or Infinity.");
        }
        
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(defaultScale, RoundingMode.HALF_UP); 
        
        BigInteger bigNum = bd.unscaledValue(); 
        BigInteger bigDen = BigInteger.TEN.pow(bd.scale()); 
        
        if (bigNum.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ||
            bigNum.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 ||
            bigDen.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) 
        {
            throw new ArithmeticException(
                "Conversion to rational number with scale " + defaultScale + " caused long overflow. " +
                "Result requires BigInteger storage."
            );
        }
        
        Rational result = new Rational(bigNum.longValue(), bigDen.longValue());
        
        this.numerator = result.numerator;
        this.denominator = result.denominator;
    }



    public Rational add(Rational other) {
        long g = gcd(this.denominator, other.denominator);
        long term1 = Math.multiplyExact(this.numerator, other.denominator / g);
        long term2 = Math.multiplyExact(other.numerator, this.denominator / g);
        long newNum = Math.addExact(term1, term2);
        long newDen = Math.multiplyExact(this.denominator / g, other.denominator); 
        return new Rational(newNum, newDen); 
    }

    public Rational multiply(Rational other) {
        long a = this.numerator; long b = this.denominator;
        long c = other.numerator; long d = other.denominator;
        long gcd_ac = gcd(Math.abs(a), d); a /= gcd_ac; d /= gcd_ac;
        long gcd_bd = gcd(Math.abs(c), b); c /= gcd_bd; b /= gcd_bd;
        long newNum = Math.multiplyExact(a, c);
        long newDen = Math.multiplyExact(b, d);
        return new Rational(newNum, newDen);
    }
    
    public Rational subtract(Rational other) {
        return this.add(new Rational(-other.numerator, other.denominator));
    }

    public Rational divide(Rational other) {
        if (other.numerator == 0) {
            throw new ArithmeticException("Division by zero rational number.");
        }
        return this.multiply(new Rational(other.denominator, other.numerator));
    }


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
    

    @Override
    public String toString() {
        if (denominator == 1) return String.valueOf(numerator);
        return numerator + "/" + denominator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(numerator, denominator);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        Rational rational = (Rational) o;
        
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
