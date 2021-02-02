# PrimitiveBuffers - Fast Protocol Buffers with Primitive Arrays

PrimitiveBuffers is a Java implementation of the Google's Protocol Buffers v3
that has been developed for low memory footprint use cases.
Currently it only supports decoding and readonly.

The default Protobuf Java library uses generic `List` for repeated fields, including primitive types.
For example, it would use `List<Integer>` for a `repeated int32` fields.
Compared to the primitive array `int[]`, `List<Integer>` needs lots of wrapper objects that requires
more memory and thus more GC activity.
This library uses primitive arrays as much as possible to reduce the overhead.

It has been used in production in Diffbot entity linking system and brings the GC down significantly.

![Gabage Collection activity on a production server](/docs/gc.png)

## Usage

Overall, we tried to keep the public API as close to Google's Protobuf-Java as possible,
so most use cases should require very few changes.

In your `build.gradle`:

```groovy
repositories {
  maven { url "https://maven.pkg.github.com/diffbot/primibuf" }
}

dependencies {
  implementation 'com.diffbot.primibuf:primibuf-runtime:0.3'
}

protobuf {
  protoc {
    artifact = 'com.google.protobuf:protoc:3.5.1'
  }
  plugins {
    primibuf {
      artifact = 'com.diffbot.primibuf:primibuf-generator:0.3:protoc@jar'
    }
  }
  generateProtoTasks {
    all().each { task ->
      task.builtins { }
      task.plugins {
        primibuf {
          outputSubDir = 'java'
        }
      }
    }
  }
}
```

## Limitations

* Only protobuf 3 is officially supported
    * We don't support custom default value

## Acknowledgement

The library was forked from https://github.com/HebiRobotics/QuickBuffers.
