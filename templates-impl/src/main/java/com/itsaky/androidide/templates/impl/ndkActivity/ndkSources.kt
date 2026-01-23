package com.itsaky.androidide.templates.impl.ndkActivity

import com.itsaky.androidide.templates.base.AndroidModuleTemplateBuilder
import com.itsaky.androidide.templates.base.NdkModuleTemplateBuilder
import com.itsaky.androidide.templates.impl.base.baseLayoutContentMain

internal fun emptyLayoutSrc() = baseLayoutContentMain()

internal fun AndroidModuleTemplateBuilder.ndkActivitySrcKt(): String {
    return """
package ${data.packageName}

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import ${data.packageName}.databinding.ActivityMainBinding

public class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    
    private val binding: ActivityMainBinding
      get() = checkNotNull(_binding) { "Activity has been destroyed" }
    
    // Load the native library
    static {
        System.loadLibrary("native-lib");
    }

    // Declare the native method
    public native String stringFromJNI();

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate and get instance of binding
        _binding = ActivityMainBinding.inflate(layoutInflater)

        // set content view to binding's root
        setContentView(binding.root)

        // Call JNI method and display result
        binding.textView.setText(stringFromJNI());
    }
    
    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
"""
}

internal fun AndroidModuleTemplateBuilder.ndkActivitySrcJava(): String {
    return """
package ${data.packageName};

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import ${data.packageName}.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;

    // Load the native library  
    static {
        System.loadLibrary("native-lib");
    }

    // Declare the native method
    public native String stringFromJNI();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Inflate and get instance of binding
        binding = ActivityMainBinding.inflate(getLayoutInflater());

        // set content view to binding's root
        setContentView(binding.getRoot());

        // Call JNI method and display result
        binding.textView.setText(stringFromJNI());
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.binding = null;
    }
}
"""
}

internal fun NdkModuleTemplateBuilder.ndkCpp(): String {
    val jniPrefix = data.packageName.replace('.', '_')
    return """
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_${jniPrefix}_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("Hello from C++");
}
""".trimIndent()
}

internal fun NdkModuleTemplateBuilder.ndkCMakeLists(): String {
    val appName = data.packageName.substringAfterLast('.')
    return """
cmake_minimum_required(VERSION 3.10.2)

project("$appName")

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(native-lib ${'$'}{log-lib})
""".trimIndent()
}
