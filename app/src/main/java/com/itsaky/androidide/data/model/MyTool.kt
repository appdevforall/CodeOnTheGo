package com.itsaky.androidide.data.model

import com.google.firebase.ai.type.FunctionDeclaration

data class MyTool(
    val name: String,
    val description: String,
    val declaration: FunctionDeclaration
)