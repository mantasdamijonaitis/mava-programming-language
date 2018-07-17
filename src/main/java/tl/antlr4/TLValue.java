package tl.antlr4;

import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TLValue implements Comparable<TLValue> {

    public static final TLValue NULL = new TLValue();
    public static final TLValue VOID = new TLValue();

    private Object value;

    private TLValue() {
        // private constructor: only used for NULL and VOID
        value = new Object();
    }

    TLValue(Object v) {
        if(v == null) {
            throw new RuntimeException("v == null");
        }
        value = v;
        // only accept boolean, list, number or string types
        if(!(isBoolean() || isList() || isNumber() || isString())) {
            throw new RuntimeException("invalid data type: " + v + " (" + v.getClass() + ")");
        }
    }

    public Boolean asBoolean() {
        return (Boolean)value;
    }

    public Double asDouble() {
        return ((Number)value).doubleValue();
    }

    public Long asLong() {
        return ((Number)value).longValue();
    }

    @SuppressWarnings("unchecked")
    public List<TLValue> asList() {
        return (List<TLValue>)value;
    }

    public String asString() {
        return (String)value;
    }

    @Override
    public int compareTo(TLValue that) {
        if(this.isNumber() && that.isNumber()) {
            if(this.equals(that)) {
                return 0;
            }
            else {
                return this.asDouble().compareTo(that.asDouble());
            }
        }
        else if(this.isString() && that.isString()) {
            return this.asString().compareTo(that.asString());
        }
        else {
            throw new RuntimeException("illegal expression: can't compare `" + this + "` to `" + that + "`");
        }
    }

    @Override
    public boolean equals(Object o) {
        if(this == VOID || o == VOID) {
            throw new RuntimeException("can't use VOID: " + this + " ==/!= " + o);
        }
        if(this == o) {
            return true;
        }
        if(o == null || this.getClass() != o.getClass()) {
            return false;
        }
        TLValue that = (TLValue)o;
        if(this.isNumber() && that.isNumber()) {
            double diff = Math.abs(this.asDouble() - that.asDouble());
            return diff < 0.00000000001;
        }
        else {
            return this.value.equals(that.value);
        }
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public boolean isList() {
        return value instanceof List<?>;
    }

    public boolean isNull() {
        return this == NULL;
    }

    public boolean isVoid() {
        return this == VOID;
    }

    public boolean isString() {
        return value instanceof String;
    }

    public static double[][] toMatrix(TLValue value) {
        List<double[]> resultList = value.asList()
                .stream().map(x -> x.asList().stream()
                        .mapToDouble(y -> y.asDouble()).toArray()).collect(Collectors.toList());
        double[][] resultArray = new double[resultList.size()][];
        resultArray = resultList.toArray(resultArray);
        return resultList.toArray(resultArray);
    }

    public static double[] toVector(TLValue value) {
        return value.asList().stream().mapToDouble(x -> x.asDouble()).toArray();
    }

    public boolean isVector() {
        return isList() && asList().size() > 0 && !asList().get(0).isList();
    }

    public static TLValue fromMatrix(RealMatrix realMatrix) {
        double[][] resultArray = realMatrix.getData();
        List<TLValue> resultList =
                Arrays.stream(resultArray).map(x -> new TLValue(Arrays.stream(x).mapToObj(y -> y).collect(Collectors.toList())))
                        .collect(Collectors.toList());
        return new TLValue(resultList);
    }

    public boolean isMatrix() {
        return isList() && asList().size() > 0 && asList().get(0).isList();
    }

    @Override
    public String toString() {
        if (isNull()) {
            return "NULL";
        }
        if (isVoid()) {
            return "VOID";
        }
        return String.valueOf(value);
    }
}
