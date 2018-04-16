package org.robolectric.annotation.processing.validator;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.robolectric.annotation.Implementation;

import static org.robolectric.annotation.processing.validator.ImplementsValidator.CONSTRUCTOR_METHOD_NAME;
import static org.robolectric.annotation.processing.validator.ImplementsValidator.STATIC_INITIALIZER_METHOD_NAME;
import static org.robolectric.annotation.processing.validator.ImplementsValidator.getClassFQName;

class SdkStore {
  private final Map<Integer, Sdk> sdks = new TreeMap<>();

  SdkStore() {
    this(loadFromPropertiesFile("/sdks.properties"));
  }

  private static Properties loadFromPropertiesFile(String resourceFileName) {
    try {
      Properties properties = new Properties();
      properties.load(SdkStore.class.getResourceAsStream(resourceFileName));
      return properties;
    } catch (IOException e) {
      throw new RuntimeException("failed to open " + resourceFileName, e);
    }
  }

  SdkStore(Properties properties) {
    for (String key : properties.stringPropertyNames()) {
      int sdkInt = Integer.parseInt(key);
      String path = properties.getProperty(key);
      sdks.put(sdkInt, new Sdk(sdkInt, path));
    }
  }

  List<Sdk> sdksMatching(Implementation implementation, int classMinSdk, int classMaxSdk) {
    int minSdk = implementation == null ? -1 : implementation.minSdk();
    if (minSdk == -1) {
      minSdk = 0;
    }
    if (classMinSdk > minSdk) {
      minSdk = classMinSdk;
    }

    int maxSdk = implementation == null ? -1 : implementation.maxSdk();
    if (maxSdk == -1) {
      maxSdk = Integer.MAX_VALUE;
    }
    if (classMaxSdk != -1 && classMaxSdk < maxSdk) {
      maxSdk = classMaxSdk;
    }

    ArrayList<Sdk> matchingSdks = new ArrayList<>();
    for (Map.Entry<Integer, Sdk> entry : this.sdks.entrySet()) {
      Integer sdkInt = entry.getKey();
      if (sdkInt >= minSdk && sdkInt <= maxSdk) {
        matchingSdks.add(entry.getValue());
      }
    }
    return matchingSdks;
  }

  static class Sdk {
    private static final ClassInfo NULL_CLASS_INFO = new ClassInfo();

    final int sdkInt;
    private final String path;
    private JarFile jarFile;
    private final Map<String, ClassInfo> classInfos = new HashMap<>();

    public Sdk(int sdkInt, String path) {
      this.sdkInt = sdkInt;
      this.path = path;
    }

    public void verifyMethod(Messager messager, TypeElement sdkClassElem, ExecutableElement methodElement) {
      String className = getClassFQName(sdkClassElem);
      ClassInfo classInfo = getClassInfo(className);

      if (classInfo == null) {
        messager.printMessage(Diagnostic.Kind.ERROR,
            "No such class " + className + " in SDK level " + sdkInt, methodElement);
      } else {
        MethodExtraInfo sdkMethod = classInfo.findMethod(methodElement);
        if (sdkMethod == null) {
          messager.printMessage(Diagnostic.Kind.ERROR,
              "No such method " + className + "." + methodElement.getSimpleName()
                  + " in SDK level " + sdkInt, methodElement);
        } else {
          MethodExtraInfo implMethod = new MethodExtraInfo(methodElement);
          if (sdkMethod.equals(implMethod)) {
            if (implMethod.isStatic != sdkMethod.isStatic) {
              messager.printMessage(Diagnostic.Kind.ERROR,
                  "@Implementation for " + methodElement.getSimpleName() +
                      " is " + (implMethod.isStatic ? "static" : "not static") +
                      " unlike the method in SDK level " + sdkInt, methodElement);
            }
            if (!implMethod.returnType.equals(sdkMethod.returnType)) {
              messager.printMessage(Diagnostic.Kind.ERROR,
                  "@Implementation for " + methodElement.getSimpleName() +
                      " has a return type of " + implMethod.returnType +
                      ", not " + sdkMethod.returnType + " as in SDK level " + sdkInt, methodElement);
            }
          }
        }
      }
    }

    synchronized private ClassInfo getClassInfo(String name) {
      if (jarFile == null) {
        try {
          jarFile = new JarFile(path);
        } catch (IOException e) {
          throw new RuntimeException("failed to open SDK " + sdkInt + " at " + path, e);
        }
      }

      ClassInfo classInfo = classInfos.get(name);
      if (classInfo == null) {
        ClassNode classNode = loadClassNode(name);

        if (classNode == null) {
          classInfos.put(name, NULL_CLASS_INFO);
        } else {
          classInfo = new ClassInfo(classNode);
          classInfos.put(name, classInfo);
        }
      }

      return classInfo == NULL_CLASS_INFO ? null : classInfo;
    }

    private ClassNode loadClassNode(String name) {
      String classFileName = name.replace('.', '/') + ".class";
      ZipEntry entry = jarFile.getEntry(classFileName);
      if (entry == null) {
        System.out.println(classFileName + " entry null in " + path);
        return null;
      }
      InputStream inputStream;
      try {
        inputStream = jarFile.getInputStream(entry);
      } catch (IOException e) {
        throw new RuntimeException("failed to file " + classFileName + " in " + path, e);
      }

      try {
        ClassReader classReader = new ClassReader(inputStream);
        ClassNode classNode = new ClassNode();
        classReader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return classNode;
      } catch (IOException e) {
        throw new RuntimeException("failed to load " + classFileName + " in " + path, e);
      } finally {
        try {
          inputStream.close();
        } catch (IOException e) {
          // ignore
        }
      }
    }
  }

  static class ClassInfo {
    private final Map<MethodInfo, MethodExtraInfo> methods = new HashMap<>();

    private ClassInfo() {
    }

    public ClassInfo(ClassNode classNode) {
      for (Object method_ : classNode.methods) {
        MethodNode method = ((MethodNode) method_);
        MethodInfo methodInfo = new MethodInfo(method);
        MethodExtraInfo methodExtraInfo = new MethodExtraInfo(method);
        methods.put(methodInfo, methodExtraInfo);
        methods.put(methodInfo.erase(), methodExtraInfo);
      }
    }

    MethodExtraInfo findMethod(ExecutableElement methodElement) {
      MethodInfo methodInfo = new MethodInfo(methodElement);
      MethodExtraInfo methodExtraInfo = methods.get(methodInfo);
      if (methodExtraInfo == null) {
        methodExtraInfo = methods.get(methodInfo.erase());
      }
      return methodExtraInfo;
    }
  }

  static class MethodInfo {
    private final String name;
    private final List<String> paramTypes = new ArrayList<>();

    public MethodInfo(MethodNode method) {
      this.name = method.name;
      for (Type type : Type.getArgumentTypes(method.desc)) {
        paramTypes.add(type.getClassName());
      }
    }

    public MethodInfo(String name, int size) {
      this.name = name;
      for (int i = 0; i < size; i++) {
        paramTypes.add("java.lang.Object");
      }
    }

    public MethodInfo(ExecutableElement methodElement) {
      this.name = cleanMethodName(methodElement);

      for (VariableElement variableElement : methodElement.getParameters()) {
        paramTypes.add(variableElement.asType().toString());
      }
    }

    private String cleanMethodName(ExecutableElement methodElement) {
      String name = methodElement.getSimpleName().toString();
      if (CONSTRUCTOR_METHOD_NAME.equals(name)) {
        return "<init>";
      } else if (STATIC_INITIALIZER_METHOD_NAME.equals(name)) {
        return "<clinit>";
      } else {
        return name;
      }
    }

    public MethodInfo erase() {
      return new MethodInfo(name, paramTypes.size());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MethodInfo that = (MethodInfo) o;
      return Objects.equals(name, that.name) &&
          Objects.equals(paramTypes, that.paramTypes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, paramTypes);
    }
  }

  static class MethodExtraInfo {
    private final boolean isStatic;
    private final String returnType;

    public MethodExtraInfo(MethodNode method) {
      this.isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
      this.returnType = Type.getReturnType(method.desc).getClassName();
    }

    public MethodExtraInfo(ExecutableElement methodElement) {
      this.isStatic = methodElement.getModifiers().contains(Modifier.STATIC);
      this.returnType = methodElement.getReturnType().toString();
    }
  }
}
