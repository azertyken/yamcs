package org.yamcs.xtceproc;

import java.util.List;

import org.yamcs.ErrorInCommand;
import org.yamcs.parameter.EnumeratedValue;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.ArgumentType;
import org.yamcs.xtce.BinaryArgumentType;
import org.yamcs.xtce.BooleanArgumentType;
import org.yamcs.xtce.DataEncoding;
import org.yamcs.xtce.EnumeratedArgumentType;
import org.yamcs.xtce.FloatArgumentType;
import org.yamcs.xtce.FloatDataEncoding;
import org.yamcs.xtce.FloatValidRange;
import org.yamcs.xtce.IntegerArgumentType;
import org.yamcs.xtce.IntegerRange;
import org.yamcs.xtce.IntegerValidRange;
import org.yamcs.xtce.StringArgumentType;
import org.yamcs.xtce.ValueEnumeration;

public class ArgumentTypeProcessor {
    ProcessorData pdata;

    public ArgumentTypeProcessor(ProcessorData pdata) {
        if (pdata == null) {
            throw new NullPointerException();
        }
        this.pdata = pdata;
    }

    public Value decalibrate(ArgumentType atype, Value v) {
        if (atype instanceof EnumeratedArgumentType) {
            return decalibrateEnumerated((EnumeratedArgumentType) atype, v);
        } else if (atype instanceof IntegerArgumentType) {
            return decalibrateInteger((IntegerArgumentType) atype, v);
        } else if (atype instanceof FloatArgumentType) {
            return decalibrateFloat((FloatArgumentType) atype, v);
        } else if (atype instanceof StringArgumentType) {
            return decalibrateString((StringArgumentType) atype, v);
        } else if (atype instanceof BinaryArgumentType) {
            return decalibrateBinary((BinaryArgumentType) atype, v);
        } else if (atype instanceof BooleanArgumentType) {
            return decalibrateBoolean((BooleanArgumentType) atype, v);
        } else {
            throw new IllegalArgumentException("decalibration for " + atype + " not implemented");
        }
    }

    private Value decalibrateEnumerated(EnumeratedArgumentType atype, Value v) {
        if(v.getType()==Type.ENUMERATED) {
            return ValueUtility.getSint64Value(((EnumeratedValue)v).getSint64Value());
        } else if (v.getType() != Type.STRING) {
            throw new IllegalArgumentException("Enumerated decalibrations only available for enumerated values or strings");
        }

        return ValueUtility.getSint64Value(atype.decalibrate(v.getStringValue()));
    }

    private Value decalibrateInteger(IntegerArgumentType ipt, Value v) {
        if (v.getType() == Type.UINT32) {
            return doIntegerDecalibration(ipt, v.getUint32Value() & 0xFFFFFFFFL);
        } else if (v.getType() == Type.UINT64) {
            return doIntegerDecalibration(ipt, v.getUint64Value());
        } else if (v.getType() == Type.SINT32) {
            return doIntegerDecalibration(ipt, v.getSint32Value());
        } else if (v.getType() == Type.SINT64) {
            return doIntegerDecalibration(ipt, v.getSint64Value());
        } else if (v.getType() == Type.STRING) {
            return doIntegerDecalibration(ipt, Long.valueOf(v.getStringValue()));
        } else {
            throw new IllegalStateException(
                    "Unsupported raw value type '" + v.getType() + "' cannot be converted to integer");
        }
    }

    private Value doIntegerDecalibration(IntegerArgumentType ipt, long v) {
        DataEncoding de = ipt.getEncoding();
        if (de instanceof FloatDataEncoding) {
            return doFloatDecalibration(ipt.getEncoding(), ipt.getSizeInBits(), v);
        }

        CalibratorProc calibrator = pdata.getDecalibrator(ipt.getEncoding());

        Value raw;
        long longDecalValue = (calibrator == null) ? v : (long) calibrator.calibrate(v);

        if (ipt.getSizeInBits() <= 32) {
            if (ipt.isSigned()) {
                raw = ValueUtility.getSint32Value((int) longDecalValue);
            } else {
                raw = ValueUtility.getUint32Value((int) longDecalValue);
            }
        } else {
            if (ipt.isSigned()) {
                raw = ValueUtility.getSint64Value(longDecalValue);
            } else {
                raw = ValueUtility.getUint64Value(longDecalValue);
            }
        }
        return raw;
    }

    private Value decalibrateBoolean(BooleanArgumentType ipt, Value v) {
        if (v.getType() != Type.BOOLEAN) {
            throw new IllegalStateException(
                    "Unsupported raw value type '" + v.getType() + "' cannot be converted to boolean");
        }
        return v;
    }

    private Value decalibrateFloat(FloatArgumentType fat, Value v) {
        if (v.getType() == Type.FLOAT) {
            return doFloatDecalibration(fat.getEncoding(), fat.getSizeInBits(), v.getFloatValue());
        } else if (v.getType() == Type.DOUBLE) {
            return doFloatDecalibration(fat.getEncoding(), fat.getSizeInBits(), v.getDoubleValue());
        } else if (v.getType() == Type.STRING) {
            return doFloatDecalibration(fat.getEncoding(), fat.getSizeInBits(), Double.valueOf(v.getStringValue()));
        } else if (v.getType() == Type.UINT32) {
            return doFloatDecalibration(fat.getEncoding(), fat.getSizeInBits(), v.getUint32Value());
        } else if (v.getType() == Type.UINT64) {
            return doFloatDecalibration(fat.getEncoding(), fat.getSizeInBits(), v.getUint64Value());
        } else if (v.getType() == Type.SINT32) {
            return doFloatDecalibration(fat.getEncoding(), fat.getSizeInBits(), v.getSint32Value());
        } else if (v.getType() == Type.SINT64) {
            return doFloatDecalibration(fat.getEncoding(), fat.getSizeInBits(), v.getSint64Value());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported value type '" + v.getType() + "' cannot be converted to float");
        }
    }

    private Value doFloatDecalibration(DataEncoding de, int sizeInBits, double doubleValue) {
        CalibratorProc calibrator = pdata.getDecalibrator(de);

        double doubleCalValue = (calibrator == null) ? doubleValue : calibrator.calibrate(doubleValue);
        Value raw;
        if (sizeInBits == 32) {
            raw = ValueUtility.getFloatValue((float) doubleCalValue);
        } else {
            raw = ValueUtility.getDoubleValue(doubleCalValue);
        }
        return raw;
    }

    private static Value decalibrateString(StringArgumentType sat, Value v) {
        Value raw;
        if (v.getType() == Type.STRING) {
            raw = v;
        } else {
            throw new IllegalStateException(
                    "Unsupported value type '" + v.getType() + "' cannot be converted to string");
        }
        return raw;
    }

    private static Value decalibrateBinary(BinaryArgumentType bat, Value v) {
        Value raw;
        if (v.getType() == Type.BINARY) {
            raw = v;
        } else {
            throw new IllegalStateException(
                    "Unsupported value type '" + v.getType() + "' cannot be converted to binary");
        }
        return raw;
    }

    public static void checkRange(ArgumentType type, Object o) throws ErrorInCommand {
        if (type instanceof IntegerArgumentType) {
            IntegerArgumentType intType = (IntegerArgumentType) type;

            long l = (Long) o;
            ;
            IntegerValidRange vr = ((IntegerArgumentType) type).getValidRange();
            if (vr != null) {
                if (intType.isSigned() && !ValidRangeChecker.checkIntegerRange(vr, l)) {
                    throw new ErrorInCommand("Value " + l + " is not in the range required for the type " + type);
                } else if (!intType.isSigned() && !ValidRangeChecker.checkUnsignedIntegerRange(vr, l)) {
                    throw new ErrorInCommand("Value " + l + " is not in the range required for the type " + type);
                }
            }
        } else if (type instanceof FloatArgumentType) {
            double d = (Double) o;
            FloatValidRange vr = ((FloatArgumentType) type).getValidRange();
            if (vr != null) {
                if (!ValidRangeChecker.checkFloatRange(vr, d)) {
                    throw new ErrorInCommand("Value " + d + " is not in the range required for the type " + type);
                }
            }
        } else if (type instanceof StringArgumentType) {
            String v = (String) o;
            IntegerRange r = ((StringArgumentType) type).getSizeRangeInCharacters();

            if (r != null) {
                int length = v.length();
                if (length < r.getMinInclusive()) {
                    throw new ErrorInCommand("Value " + v + " supplied for parameter fo type " + type
                            + " does not satisfy minimum length of " + r.getMinInclusive());
                }
                if (length > r.getMaxInclusive()) {
                    throw new ErrorInCommand("Value " + v + " supplied for parameter fo type " + type
                            + " does not satisfy maximum length of " + r.getMaxInclusive());
                }
            }

        } else if (type instanceof BinaryArgumentType) {
            byte[] b = (byte[]) o;
            IntegerRange r = ((BinaryArgumentType) type).getSizeRangeInBytes();

            if (r != null) {
                int length = b.length;
                if (length < r.getMinInclusive()) {
                    throw new ErrorInCommand(
                            "Value " + StringConverter.arrayToHexString(b) + " supplied for parameter fo type " + type
                                    + " does not satisfy minimum length of " + r.getMinInclusive());
                }
                if (length > r.getMaxInclusive()) {
                    throw new ErrorInCommand(
                            "Value " + StringConverter.arrayToHexString(b) + " supplied for parameter fo type " + type
                                    + " does not satisfy maximum length of " + r.getMaxInclusive());
                }
            }
        } else if (type instanceof EnumeratedArgumentType) {
            EnumeratedArgumentType enumType = (EnumeratedArgumentType) type;
            List<ValueEnumeration> vlist = enumType.getValueEnumerationList();
            boolean found = false;
            String v = (String) o;

            for (ValueEnumeration ve : vlist) {
                if (ve.getLabel().equals(v)) {
                    found = true;
                }
            }
            if (!found) {
                throw new ErrorInCommand("Value '" + v
                        + "' supplied for enumeration argument cannot be found in enumeration list " + vlist);
            }
        } else if (type instanceof BooleanArgumentType) {
            // nothing to check
        } else {
            throw new IllegalArgumentException("Cannot process values of type " + type);
        }
    }
}
