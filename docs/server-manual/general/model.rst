Monitoring and Control Model
============================

Yamcs implements a fairly traditional Monitoring and Control Model. The remote system is represented through a set of **parameters** which are sampled at regular intervals.  
Yamcs assumes that parameters are not sent individually but in groups which usually (but not necessarily) are some sort of binary packets. Yamcs supports basic parameter types (int, long, float, double, boolean, timestamp, string, binary) but not yet aggregate types (aka structs in C language) - for example to represent an (x,y,z) position. Yamcs does preserve the association between parameters coming in the same group, which helps alleviating the problem of missing aggregates.

Parameters can either be received directly from the remote device or can be computed locally by **algorithms**. Algorithms in Yamcs can be implemented in Javascript or Python. Other languages that have JVM (Java Virtual Machine) based implementations could also be supported without too much trouble.

Following the XTCE standard, Yamcs distinguishes between **telemetered parameters** (= coming from remote devices), **derived** parameters (= computed by algorithms inside Yamcs), **local** parameters (= set by end-user applications) and **constant parameters** (which are just constant values defined in the mission database). In addition to these XTCE inspired parameter types, Yamcs defines **system parameters** (parameters generated by components inside Yamcs), **command** and **command history parameters**. The last two are specially scoped parameters that can be used in the context of command verifiers.


The parameters have limits associated to them and when those limits are exceeded, an **alarm** is triggered. The limits can change depending on the **context** which represent the state of the remote device. The context itself is derived from the value of other parameters.

An operator is informed of the triggered alarm in various ways depending on the end user application connected to Yamcs (e.g. red background in a display, audible alarm, sms, phone call, etc). After understanding the problem, the operator **acknowledges** the alarm, which means that it informs Yamcs that the alarm will be taken care of. This action - depending again on the remote end user application connected to Yamcs - means that other operators are not bothered anymore by the alarm.   
After the alarm has been acknowledged and the parameter goes back into limits, the alarm is **cleared** which means it is not triggered anymore.  
Before the alarm is acknowledged by an operator, it will stay triggered even if the parameter goes back into limits. An exception to this case is auto-acknowledging alarms which are cleared automatically when the parameter that triggered them goes back in limits.  

As the parameters are supposed to be sampled regularely, they also have an expiration time. After the time is exceeded, the parameters become expired - that is to say at that time the state of the remote device is considered unknown.

The remote device is controlled through the use of **(tele)-commands**. A telecommand is made up by a name and a number of **command arguments**. In order for a command to be allowed to be sent, the **command transmission constraints** (if any) have to be met. The constraints are expressed by the state of parameters (e.g. a command can be send only if a subsystem is switched on). Some commands can have an elevated **significance**, which may mean that a special privilege or an extra confirmation is required to send the command.
Once the command has been sent, it passes throguh a series of execution stages. XTCE pre-defines a series of stages (TransferredToRange, SentFromRange, Received, Accepted, etc). Yamcs does not enforce the use of these predefined stages, the user is free to choose any number of random stages. Each stage has associated a **command verifier** - this is an algorithm that will decide if the command has passed or not that stage. It is also possible to specify that the stage has been passed when a specific packet has been received.

The command text (command name and argument values), the binary packet (if binary formatted) and the different stages of the execution of the command are recorded in the **command history**.
Yamcs does not limit the information that can be added to the command history. This can be extended with and arbitrary number of (key, timestamp, value) attributes.

Instances
---------
The Yamcs instances provide means for one Yamcs server to monitor/control different payloads or sattelites or version of the payloads or satellites at the same time. Each instance has a name and a directory where all data from that instance is stored, as well as a specific Mission Database used to process data for that instance. Therefore, each time the Mission Database changes (e.g. due to an on-board software upgrade), a new instance has to be created. One strategy to deal with long duration missions which require multiple instances, is to put the old instances in readonly mode by disabling the components that inject data.


Data Types
----------

Yamcs supports the following high-level data types:

* A **parameter** is a data value corresponding to the observed value of a certain device. Parameters have different properties like Raw Value, Engineering Value, Monitoring status and Validity status. The raw and engineering values may be of scalar types (i.e int, float, string, etc), or may also be arrays and aggregated parameters (analogous to structs in C programming language).
* A **processed parameter** (abbreviated PP) is a particular type of parameter that is processed by an external (to Yamcs) entity. Yamcs does not contain information about how they are processed. The processed parameters have to be converted into Yamcs internal format (and therefore compatible with the Yamcs parameter types) in order to be propagated to the monitoring clients.
* A **telemetry packet** is a binary chunk of data containing a number of parameters in raw format. The packets are split into parameters according to the definitions contained in the Mission Database.
* **(Tele)commands** are used to control remote devices and are composed of a name and a list of arguments. The commands are transformed into binary packets according to the definition in the Mission Database.
* An **event** is a data type containing a source, type, level and message used by the payload to log certain kind of events. Yamcs generates internally a number of events. In order to extract events from telemetry, a special component called *Event Decoder* has to be written.

The high-level data types described above are modelled internally on a data structure called *tuple*. A tuple is a list of (name, value) pairs, where the names are simple strings and the values being of a few predefined basic data types. The exact definition of the Yamcs high-level data types in terms of tuple (e.g. a telemetry packet has the attributes gentime(timestamp), rectime(timestamp), packet(binary), etc) is currently hard-coded inside the java source code. In the future it might be externalised in configuration files to allow a certain degree of customisation.
