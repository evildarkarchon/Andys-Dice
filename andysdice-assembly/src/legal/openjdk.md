# OpenJDK

The `runtime\` folder is a Java runtime linked by `jlink` from an OpenJDK build: the `java.*` modules the app needs,
with their native libraries and launchers. OpenJDK is licensed under the GNU General Public License, version 2,
with the Classpath Exception.

`jlink` keeps the runtime's notices beside it, one folder per module:

- `..\..\runtime\legal\java.base\LICENSE`: the GNU General Public License, version 2, followed by the Classpath
  Exception;
- `..\..\runtime\legal\java.base\ASSEMBLY_EXCEPTION` and `ADDITIONAL_LICENSE_INFO`;
- `..\..\runtime\legal\<module>\*.md`: the notices of the third-party code inside each module.

The OpenJDK source code is at https://openjdk.org/.
