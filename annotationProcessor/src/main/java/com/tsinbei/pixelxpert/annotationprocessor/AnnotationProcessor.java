package com.tsinbei.pixelxpert.annotationprocessor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import com.tsinbei.pixelxpert.annotations.BaseModPack;
import com.tsinbei.pixelxpert.annotations.ChildProcessModPack;
import com.tsinbei.pixelxpert.annotations.MainProcessModPack;
import com.tsinbei.pixelxpert.annotations.ModPackPriority;

@SupportedAnnotationTypes("com.tsinbei.pixelxpert.annotations.BaseModPack")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class AnnotationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return true;
        }

        ClassName modPackData = ClassName.get("com.tsinbei.pixelxpert.annotations", "ModPackData");
        ParameterizedTypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), modPackData);

        MethodSpec.Builder getModPacksMethod = MethodSpec.methodBuilder("getModPacks")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(listType)
                .addStatement("$T result = new $T<>()", listType, ClassName.get(ArrayList.class));

        Set<? extends Element> modPackMetaAnnotations = roundEnv.getElementsAnnotatedWith(BaseModPack.class);

        for (Element metaAnnotation : modPackMetaAnnotations) {
            if (!(metaAnnotation instanceof TypeElement typeElement)) continue;

            @SuppressWarnings("DataFlowIssue")
            String targetPackage = typeElement.getAnnotation(BaseModPack.class).targetPackage();
            Set<? extends Element> targetModPacks = roundEnv.getElementsAnnotatedWith(typeElement);

            List<Element> sortedModPacks = targetModPacks.stream()
                    .sorted(Comparator.comparingInt(e -> {
                        ModPackPriority priority = e.getAnnotation(ModPackPriority.class);
                        return priority != null ? priority.priority() : 99;
                    }))
                    .collect(Collectors.toList());

            for (Element element : sortedModPacks) {
                if (!(element instanceof TypeElement modPackClass)) continue;

                boolean mainProcessPack = true;
                boolean childProcessPack = false;
                String childProcessName = "";

                ChildProcessModPack childProcessData = modPackClass.getAnnotation(ChildProcessModPack.class);
                if (childProcessData != null) {
                    childProcessPack = true;
                    childProcessName = childProcessData.processNameContains();
                    mainProcessPack = modPackClass.getAnnotation(MainProcessModPack.class) != null;
                }

                getModPacksMethod.addStatement("result.add(new $T($T.class, $S, $L, $L, $S))",
                        modPackData,
                        ClassName.get(modPackClass),
                        targetPackage,
                        mainProcessPack,
                        childProcessPack,
                        childProcessName);
            }
        }

        getModPacksMethod.addStatement("return result");

        TypeSpec modPacksClass = TypeSpec.classBuilder("ModPacks")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(getModPacksMethod.build())
                .build();

        JavaFile javaFile = JavaFile.builder("com.tsinbei.pixelxpert.xposed", modPacksClass)
                .build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            // In a real processor, we might want to use processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, ...)
            throw new RuntimeException(e);
        }

        return true;
    }
}
