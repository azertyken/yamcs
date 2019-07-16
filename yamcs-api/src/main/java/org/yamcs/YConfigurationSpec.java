package org.yamcs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specifies the valid structure of a {@link YConfiguration} instance. While not strictly 'validation', the spec also
 * allows defining additional metadata like the 'default' keyword which is used in the merged result of a validation.
 */
public class YConfigurationSpec {

    private static final Logger log = LoggerFactory.getLogger(YConfigurationSpec.class);

    private Map<String, Option> options = new HashMap<>();

    private List<List<String>> requiredOneOfGroups = new ArrayList<>(0);
    private List<List<String>> requireTogetherGroups = new ArrayList<>(0);
    private List<List<String>> mutuallyExclusiveGroups = new ArrayList<>(0);
    private List<WhenCondition> whenConditions = new ArrayList<>(0);

    /**
     * Add an {@link Option} to this spec.
     * 
     * @throws ConfigurationException
     *             if an option with this name is already defined.
     */
    public Option addOption(String name, OptionType type) {
        if (options.containsKey(name)) {
            throw new IllegalArgumentException("Option '" + name + "' is already defined");
        }
        Option option = new Option(name, type);
        options.put(name, option);
        return option;
    }

    /**
     * Specify a set of keys of which at least one must be specified. Note that this not enforce that only one is
     * specified. You can combine this check with {@link #mutuallyExclusive(String...)} if that is required.
     */
    public void requireOneOf(String... keys) {
        verifyKeys(keys);
        requiredOneOfGroups.add(Arrays.asList(keys));
    }

    /**
     * Specify a set of keys that must appear together. This check only applies as soon as at least one of these keys
     * has been specified.
     */
    public void requireTogether(String... keys) {
        verifyKeys(keys);
        requireTogetherGroups.add(Arrays.asList(keys));
    }

    /**
     * Specify a set of keys that are mutually exclusive. i.e. at most one of them may be specified.
     */
    public void mutuallyExclusive(String... keys) {
        verifyKeys(keys);
        mutuallyExclusiveGroups.add(Arrays.asList(keys));
    }

    /**
     * Add a condition that is only verified when <tt>key.equals(value)</tt>
     * 
     * @param key
     *            the name of an option
     * @param value
     *            the value that triggers the conditional check
     * @return an instance of {@link WhenCondition} for further configuration options
     */
    public WhenCondition when(String key, Object value) {
        verifyKeys(key);
        WhenCondition whenCondition = new WhenCondition(this, key, value);
        whenConditions.add(whenCondition);
        return whenCondition;
    }

    /**
     * Validate the given arguments according to this spec.
     * 
     * @param args
     *            the arguments to validate.
     * @return the validation result where defaults have been added to the input arguments
     * @throws ValidationException
     *             when the specified arguments did not match this specification
     */
    public YConfiguration validate(YConfiguration args) throws ValidationException {
        Map<String, Object> result = validate(args.getRoot());
        return YConfiguration.wrap(result);
    }

    /**
     * Validate the given arguments according to this spec.
     * 
     * @param args
     *            the arguments to validate, keyed by argument name.
     * @return the validation result where defaults have been added to the input arguments
     * @throws ValidationException
     *             when the specified arguments did not match this specification
     */
    public Map<String, Object> validate(Map<String, Object> args) throws ValidationException {
        return doValidate(args, "");
    }

    private Map<String, Object> doValidate(Map<String, Object> args, String parent)
            throws ValidationException {

        for (List<String> group : requiredOneOfGroups) {
            if (count(args, group) == 0) {
                String msg = "One of the following is required: " + group;
                if (!"".equals(parent)) {
                    msg += " at " + parent;
                }
                throw new ValidationException(msg);
            }
        }

        for (List<String> group : mutuallyExclusiveGroups) {
            if (count(args, group) > 1) {
                String msg = "The following arguments are mutually exclusive: " + group;
                if (!"".equals(parent)) {
                    msg += " at " + parent;
                }
                throw new ValidationException(msg);
            }
        }

        for (List<String> group : requireTogetherGroups) {
            int n = count(args, group);
            if (n > 0 && n != group.size()) {
                String msg = "The following arguments are required together: " + group;
                if (!"".equals(parent)) {
                    msg += " at " + parent;
                }
                throw new ValidationException(msg);
            }
        }

        for (WhenCondition whenCondition : whenConditions) {
            Object arg = args.get(whenCondition.key);
            if (arg != null && arg.equals(whenCondition.value)) {
                List<String> missing = whenCondition.requiredKeys.stream()
                        .filter(key -> !args.containsKey(key))
                        .collect(Collectors.toList());
                if (!missing.isEmpty()) {
                    String path = "".equals(parent) ? whenCondition.key : (parent + "->" + whenCondition.key);
                    throw new ValidationException(String.format(
                            "%s is %s but the following arguments are missing: %s",
                            path, whenCondition.value, missing));
                }
            }
        }

        // Build a new set of args where defaults have been entered
        Map<String, Object> result = new HashMap<>();

        // Check the provided arguments
        for (Entry<String, Object> entry : args.entrySet()) {
            String argName = entry.getKey();
            String path = "".equals(parent) ? argName : (parent + "->" + argName);

            Option option = options.get(argName);
            if (option == null) {
                throw new ValidationException("Unknown argument " + path);
            }

            Object arg = entry.getValue();
            Object resultArg = option.validate(arg, path);
            result.put(argName, resultArg);
        }

        for (Option option : options.values()) {
            if (option.required && !args.containsKey(option.name)) {
                String path = "".equals(parent) ? option.name : parent + "->" + option.name;
                throw new ValidationException("Missing required argument " + path);
            } else if (option.defaultValue != null) {
                result.put(option.name, option.defaultValue);
            }
        }

        return result;
    }

    private void verifyKeys(String... keys) {
        for (String key : keys) {
            if (!options.containsKey(key)) {
                throw new IllegalArgumentException("Missing argument " + key);
            }
        }
    }

    private int count(Map<String, Object> args, List<String> check) {
        int matched = 0;
        for (String key : check) {
            if (args.containsKey(key)) {
                matched++;
            }
        }
        return matched;
    }

    public static enum OptionType {
        BOOLEAN,
        INTEGER,
        FLOAT,
        LIST,
        MAP,
        STRING;

        static OptionType forArgument(Object arg) {
            if (arg instanceof String) {
                return STRING;
            } else if (arg instanceof Boolean) {
                return BOOLEAN;
            } else if (arg instanceof Integer) {
                return INTEGER;
            } else if (arg instanceof Float || arg instanceof Double) {
                return FLOAT;
            } else if (arg instanceof List) {
                return LIST;
            } else if (arg instanceof Map) {
                return MAP;
            } else {
                throw new IllegalArgumentException(
                        "Cannot derive type for argument of class " + arg.getClass().getName());
            }
        }
    }

    public static final class WhenCondition {

        private YConfigurationSpec spec;
        private String key;
        private Object value;
        private List<String> requiredKeys = new ArrayList<>();

        public WhenCondition(YConfigurationSpec spec, String key, Object value) {
            this.spec = spec;
            this.key = key;
            this.value = value;
        }

        public WhenCondition requireAll(String... keys) {
            spec.verifyKeys(keys);
            for (String key : keys) {
                requiredKeys.add(key);
            }
            return this;
        }
    }

    public static final class Option {

        private final String name;
        private final OptionType type;
        private boolean required;
        private Object defaultValue;
        private OptionType elementType;
        private String deprecationMessage;
        private List<Object> choices;
        private YConfigurationSpec spec;

        public Option(String name, OptionType type) {
            this.name = name;
            this.type = type;
        }

        /**
         * Set whether this option is required.
         */
        public Option withRequired(boolean required) {
            this.required = required;
            return this;
        }

        /**
         * Sets the default value. This is used only if the option is not required.
         */
        public Option withDefault(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        /**
         * In case the {@link #type} is set to {@link OptionType#LIST} the element type indicates the type of each
         * element of that list.
         */
        public Option withElementType(OptionType elementType) {
            this.elementType = elementType;
            return this;
        }

        /**
         * Attach a deprecation message to this option.
         */
        public Option withDeprecationMessage(String deprecationMessage) {
            this.deprecationMessage = deprecationMessage;
            return this;
        }

        /**
         * Sets the allowed values of this option.
         */
        public Option withChoices(Object... choices) {
            this.choices = Arrays.asList(choices);
            return this;
        }

        /**
         * In case the {@link #type} or the {@link #elementType} is set to {@link OptionType#MAP} this specifies the
         * options within that map.
         */
        public Option withSpec(YConfigurationSpec spec) {
            this.spec = spec;
            return this;
        }

        @SuppressWarnings("unchecked")
        private Object validate(Object arg, String path) throws ValidationException {
            if (deprecationMessage != null) {
                log.warn("Argument {} has been deprecated: {}", path, deprecationMessage);
            }

            OptionType argType = OptionType.forArgument(arg);
            if (argType != type) {
                throw new ValidationException(String.format(
                        "%s is of type %s, but should be %s instead",
                        path, argType, type));
            }

            if (choices != null && !choices.contains(arg)) {
                throw new ValidationException(String.format(
                        "%s should be one of %s", name, choices));
            }

            if (type == OptionType.LIST) {
                List<Object> resultList = new ArrayList<>();
                ListIterator<Object> it = ((List<Object>) arg).listIterator();
                while (it.hasNext()) {
                    String elPath = path + "[" + it.nextIndex() + "]";
                    Object argElement = it.next();

                    OptionType argElementType = OptionType.forArgument(argElement);
                    if (argElementType != elementType) {
                        throw new ValidationException(String.format(
                                "%s should be of type %s", elPath, elementType));
                    }

                    if (elementType == OptionType.LIST) {
                        throw new UnsupportedOperationException("List of lists cannot be validated");
                    } else if (elementType == OptionType.MAP) {
                        Map<String, Object> m = (Map<String, Object>) argElement;
                        Object resultArg = spec.doValidate(m, elPath);
                        resultList.add(resultArg);
                    } else {
                        resultList.add(argElement);
                    }
                }
                return resultList;
            } else if (type == OptionType.MAP) {
                return spec.doValidate((Map<String, Object>) arg, path);
            } else {
                return arg;
            }
        }
    }
}