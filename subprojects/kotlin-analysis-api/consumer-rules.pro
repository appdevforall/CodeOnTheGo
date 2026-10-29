# ADFA-3604: keep classes reflectively instantiated by the bundled Kotlin
# Analysis API jar (analysis-api-standalone-embeddable-for-ide). IntelliJ's
# plugin system resolves these by fully-qualified name from XML extension
# descriptors (META-INF/Core.xml, CoreImpl.xml, JavaPsiPlugin.xml,
# META-INF/analysis-api/*.xml) and java.util.ServiceLoader entries
# (META-INF/services/*) bundled inside the jar, so R8 can't trace them from
# bytecode reachability alone.
#
# This list is generated, not hand-picked. It was produced by scanning the
# jar's own descriptors rather than guessing at attribute names, so it
# survives IntelliJ bean types that don't use "implementationClass":
#
#   1. Build the module once so the jar is cached, then extract its
#      descriptors (path/URL come from this module's build.gradle.kts):
#        JAR=build/externalAssetsCache/kt-android.jar
#        unzip -o "$JAR" 'META-INF/services/*' 'META-INF/Core.xml' \
#          'META-INF/CoreImpl.xml' 'META-INF/JavaPsiPlugin.xml' \
#          'META-INF/analysis-api/*.xml' 'META-INF/extensions/*.xml' \
#          'intellij.java.frontback.psi*.xml' -d /tmp/kt-xml
#   2. For every META-INF/services/<Interface> file, keep the interface
#      name (the filename) and every implementation class listed inside.
#   3. For every extracted .xml file, scan each quoted attribute value and
#      keep tokens shaped like a FQCN (dot-separated, >=3 segments, last
#      segment starts uppercase). Scanning all attributes rather than only
#      "implementationClass"/"serviceImplementation" is what catches
#      extension points that name their impl attribute after their own
#      bean class.
#   4. Dedupe, sort, emit "-keep class <FQCN> { *; }" per entry. XML has no
#      way to distinguish "." from "$" in a nested class name, so diff any
#      new entries containing a package segment that repeats a class name
#      pattern (e.g. "Foo.Bar" where Foo is itself a class) and fix those to
#      "Foo$Bar" by hand, as was needed for
#      org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages$Extension.
#   5. The jar relocates IntelliJ classes from com.intellij to
#      org.jetbrains.kotlin.com.intellij, but its descriptors still use the
#      original names. Prefix every com.intellij.* entry with
#      "org.jetbrains.kotlin.", or the rule matches no class (ADFA-4647).
#
# To re-run after a Kotlin Analysis API / IntelliJ platform version bump:
# regenerate against the new jar, diff the new FQCN list against the
# "-keep class" lines below, add new entries, and double-check any entry
# that disappeared actually left the new jar's descriptors before removing
# it. Rebuild a release APK afterward to confirm the shrink still succeeds
# (see ADFA-3604 for the dex-size baseline this was meant to preserve).
# Also re-check the StreamEx note in app/proguard-rules.pro (ADFA-4647): R8
# may trace StreamEx only while all its callers stay under
# org.jetbrains.kotlin.** and it does no name-based reflection.

-keep class org.jetbrains.kotlin.com.intellij.DynamicBundle$LanguageBundleEP { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.ImportFilter { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.InferredAnnotationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.JavaContainerProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.TestFrameworks { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.TestFrameworksImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.controlflow.ControlFlowProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.highlighting.JavaHighlightUsagesDescriptionProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.highlighting.JavaReadWriteAccessDetector { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.inline.completion.render.InlineCompletionInlayRenderer { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.javadoc.SnippetDocTagMethodImplementor { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.multiverse.CodeInsightContextManager { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.multiverse.CodeInsightContextManagerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.multiverse.CodeInsightContextPresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.multiverse.CodeInsightContextProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.multiverse.MultiverseEnabler { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeInsight.runner.JavaMainMethodProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.codeStyle.ReferenceAdjuster { *; }
-keep class org.jetbrains.kotlin.com.intellij.diagnostic.FreezeProfiler { *; }
-keep class org.jetbrains.kotlin.com.intellij.diagnostic.PluginProblemReporter { *; }
-keep class org.jetbrains.kotlin.com.intellij.diagnostic.PluginProblemReporterImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.ide.FileIconPatcher { *; }
-keep class org.jetbrains.kotlin.com.intellij.ide.FileIconProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.ide.IconLayerProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.ide.IconProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.ide.debug.ApplicationStateDebugSupport { *; }
-keep class org.jetbrains.kotlin.com.intellij.java.frontback.psi.impl.JavaLangLevelProjectCustomDataSynchronizer { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.ASTFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.Commenter { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.LanguageExtensionPoint { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.MetaLanguage { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.ParserDefinition { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.PsiBuilderFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.TokenSeparatorGenerator { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.impl.PsiBuilderFactoryImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.injection.MultiHostInjector { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.injection.general.LanguageInjectionContributor { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.injection.general.LanguageInjectionPerformer { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.java.JShellParserDefinition { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.java.JavaCommenter { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguageDumbAware { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.java.JavaParserDefinition { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.java.source.JavaDeclarationSearcher { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.jvm.JvmLanguageDumbAware { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.jvm.JvmMetaLanguage { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmElementProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmFacade { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmFacadeImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.lang.jvm.source.JvmDeclarationSearcher { *; }
-keep class org.jetbrains.kotlin.com.intellij.model.Symbol { *; }
-keep class org.jetbrains.kotlin.com.intellij.model.psi.ImplicitReferenceProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.model.psi.PsiExternalReferenceHost { *; }
-keep class org.jetbrains.kotlin.com.intellij.model.psi.PsiSymbolReference { *; }
-keep class org.jetbrains.kotlin.com.intellij.model.psi.PsiSymbolReferenceProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.model.psi.PsiSymbolReferenceProviderBean { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.components.ServiceDescriptor { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentWriteAccessGuard { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.fileTypes.BinaryFileDecompiler { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeExtensionPoint { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry$FileTypeDetector { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.startup.InitProjectActivity { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.startup.ProjectActivity { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.startup.StartupActivity$RequiredForSmartMode { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.util.ClassExtensionPoint { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.util.Condition { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.AsyncFileListener { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.CompactVirtualFileSetFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManagerListener { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePreCloseCheck { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSetFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.WritingAccessProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.VirtualFileManagerImpl$VirtualFileSystemBean { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.limits.FileSizeLimit { *; }
-keep class org.jetbrains.kotlin.com.intellij.openapi.vfs.transformer.TextPresentationTransformer { *; }
-keep class org.jetbrains.kotlin.com.intellij.platform.eel.provider.EelProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.pom.PomDeclarationSearcher { *; }
-keep class org.jetbrains.kotlin.com.intellij.pom.PomModel { *; }
-keep class org.jetbrains.kotlin.com.intellij.pom.core.impl.PomModelImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.pom.java.InternalPersistentJavaLanguageLevelReaderService { *; }
-keep class org.jetbrains.kotlin.com.intellij.pom.java.InternalPersistentJavaLanguageLevelReaderService$DefaultImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.pom.java.LanguageFeatureProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.BasicInspectionVisitorBean { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.ClassFileViewProviderFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.ClassTypePointerFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.ElementManipulator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.FileViewProviderFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.JVMElementFactoryProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.JavaCompilerConfigurationProxy { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.JavaModuleSystem { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.JvmPsiConversionHelper { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.LanguageSubstitutor { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiAnnotationSupport { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiClass { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiElementFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiField { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiFragment { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiJavaModule { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiManager { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiMethod { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiPackage { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiParameter { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiParserFacade { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiRecordComponent { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiReferenceContributor { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiReferenceService { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiReferenceServiceImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiResolveHelper { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutorFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.SmartPointerManager { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.SmartTypePointerManager { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.codeStyle.ReferenceAdjuster { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.compiled.ClassFileDecompilers$Decompiler { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.ConstantExpressionEvaluator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.ExpressionConverter { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.JavaClassSupersImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.JavaPsiFacadeImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.JvmPsiConversionHelperImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiCachedValuesFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFactoryImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiExpressionEvaluator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiFileEx$BatchReferenceProcessingSuppressor { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiFileFactoryImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiJavaModuleTreeChangePreprocessor { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiModificationTrackerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiNameHelperImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiParserFacadeImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiSubstitutorFactoryImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.RecordAugmentProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClassFileDecompiler { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClassFileStubBuilder { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsDecompilerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.file.PsiDirectoryFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.file.PsiDirectoryFactoryImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.JavaStubElementTypes { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.search.MethodSuperSearcher { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.PsiClassReferenceTypePointerFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.smartPointers.SmartTypePointerManagerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.JShellPsiAugmentProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.javadoc.JavadocManagerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.javadoc.PsiSnippetDocTagImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.javadoc.SnippetDocTagManipulator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.JavaResolveCache { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.PsiResolveHelperImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.ResolveCache { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.PsiReferenceContributorEP { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistryImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.impl.manipulators.FragmentManipulator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.impl.manipulators.PsiDocTagValueManipulator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.reference.impl.manipulators.StringLiteralManipulator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaASTFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaTreeGenerator { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeCopyHandler { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.javadoc.CustomJavadocTagProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.javadoc.JavadocManager { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.javadoc.JavadocTagInfo { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.javadoc.PsiDocTag { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.presentation.java.ClassPresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.presentation.java.FieldPresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.presentation.java.JavaModulePresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.presentation.java.MethodPresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.presentation.java.PackagePresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.presentation.java.RecordComponentPresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.presentation.java.VariablePresentationProvider { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.stubs.StubElementRegistryService { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.stubs.StubElementRegistryServiceImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.stubs.StubElementTypeHolderEP { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.templateLanguages.TreePatcher { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.util.CachedValuesManager { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.util.JavaClassSupers { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.util.JavaMultiReleaseModuleSupport { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker { *; }
-keep class org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker$Listener { *; }
-keep class org.jetbrains.kotlin.com.intellij.testIntegration.TestFramework { *; }
-keep class org.jetbrains.kotlin.com.intellij.util.CachedValuesFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.util.CachedValuesManagerImpl { *; }
-keep class org.jetbrains.kotlin.com.intellij.util.QueryExecutor { *; }
-keep class org.jetbrains.kotlin.com.intellij.util.messages.MessageBusFactory { *; }
-keep class org.jetbrains.kotlin.com.intellij.util.messages.impl.MessageBusFactoryImpl { *; }
-keep class jaxp.xml.stream.XMLEventFactory { *; }
-keep class jaxp.xml.stream.XMLInputFactory { *; }
-keep class jaxp.xml.stream.XMLOutputFactory { *; }
-keep class kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoader { *; }
-keep class kotlin.reflect.jvm.internal.impl.load.java.ErasedOverridabilityCondition { *; }
-keep class kotlin.reflect.jvm.internal.impl.load.java.FieldOverridabilityCondition { *; }
-keep class kotlin.reflect.jvm.internal.impl.load.java.JavaIncompatibilityRulesOverridabilityCondition { *; }
-keep class kotlin.reflect.jvm.internal.impl.resolve.ExternalOverridabilityCondition { *; }
-keep class kotlin.reflect.jvm.internal.impl.serialization.deserialization.builtins.BuiltInsLoaderImpl { *; }
-keep class org.jetbrains.kotlin.DescriptorSerializerPlugin { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.KaFirDefaultImportsProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider$SessionInvalidationListener { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.modification.KaFirSourceModificationService { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.projectStructure.KaFirLibraryTargetPlatformContentScopeRefiner { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaAdditionalKDocResolutionProviderAdapter { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirArrayAccessReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirCollectionLiteralReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirConstructorDelegationReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirDestructuringDeclarationReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirForLoopInReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirInvokeFunctionReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirKDocReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirPropertyDelegationMethodsReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KaFirSimpleNameReference$Provider { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.KotlinFirKDocResolutionStrategyProviderService { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.references.ReadWriteAccessCheckerFirImpl { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.statistics.KaFirStatisticsService { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.utils.KaFirCacheCleaner { *; }
-keep class org.jetbrains.kotlin.analysis.api.fir.utils.KaFirStopWorldCacheCleaner { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseJavaModuleResolver { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseKotlinJavaPsiFacade { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.java.source.JavaElementSourceWithSmartPointerFactory { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.lifetime.KaBaseLifetimeTracker { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionChecker { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionRegistry { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseContentScopeProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseModuleProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseResolutionScopeProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaResolveExtensionToContentScopeRefinerBridge { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KotlinOptimizingGlobalSearchScopeMerger { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KotlinResolveExtensionGeneratedFileScopeMergeStrategy { *; }
-keep class org.jetbrains.kotlin.analysis.api.impl.base.references.KotlinReferenceProvidersServiceImpl { *; }
-keep class org.jetbrains.kotlin.analysis.api.imports.KaDefaultImportsProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.permissions.KaAnalysisPermissionRegistry { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.KotlinMessageBusProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.KotlinProjectMessageBusProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.lifetime.KaLifetimeTracker { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEventListener { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.permissions.KaAnalysisPermissionChecker { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaContentScopeProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaGlobalSearchScopeMerger { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinContentScopeRefiner { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinGlobalSearchScopeMergeStrategy { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.resolution.KaResolutionActivityTracker { *; }
-keep class org.jetbrains.kotlin.analysis.api.platform.statistics.KaStatisticsService { *; }
-keep class org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.session.KaSessionProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneFirDirectInheritorsProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.symbols.AdditionalKDocResolutionProvider { *; }
-keep class org.jetbrains.kotlin.analysis.api.symbols.KaAdditionalKDocResolutionProvider { *; }
-keep class org.jetbrains.kotlin.analysis.decompiled.light.classes.origin.KotlinDeclarationInCompiledFileSearcher { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.js.KotlinJavaScriptMetaFileType { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.konan.KlibLoadingMetadataCache { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.konan.KlibMetaFileType { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.konan.KotlinKlibMetadataDecompiler { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProvider { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProviderCliImpl { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.K2KotlinBuiltInDecompilationInterceptor { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.K2KotlinBuiltInStubVersionOffsetProvider { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInDecompilationInterceptor { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInDecompiler { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInFileType { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInStubVersionOffsetProvider { *; }
-keep class org.jetbrains.kotlin.analysis.decompiler.psi.KotlinClassFileDecompiler { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirGlobalResolveComponents { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.LLResolutionFacadeService { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.api.services.LLFirElementByPsiElementChooser { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirDeclarationModificationService { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationListener { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationListenerForCodeFragments { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker$Listener { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirResolutionActivityTracker { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.projectStructure.LLFirBuiltinsSessionFactory { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.services.LLRealFirElementByPsiElementChooser { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationEventPublisher { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationListener { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService$LLKotlinModificationEventListener { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService$LLPsiModificationTrackerListener { *; }
-keep class org.jetbrains.kotlin.analysis.low.level.api.fir.statistics.LLStatisticsService { *; }
-keep class org.jetbrains.kotlin.asJava.KotlinAsJavaSupport { *; }
-keep class org.jetbrains.kotlin.asJava.finder.JavaElementFinder { *; }
-keep class org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension { *; }
-keep class org.jetbrains.kotlin.backend.jvm.extensions.ClassGeneratorExtension { *; }
-keep class org.jetbrains.kotlin.builtins.BuiltInsLoader { *; }
-keep class org.jetbrains.kotlin.codegen.extensions.ClassBuilderInterceptorExtension { *; }
-keep class org.jetbrains.kotlin.codegen.signature.KotlinToJvmSignatureMapperImpl { *; }
-keep class org.jetbrains.kotlin.com.fasterxml.aalto.stax.EventFactoryImpl { *; }
-keep class org.jetbrains.kotlin.com.fasterxml.aalto.stax.InputFactoryImpl { *; }
-keep class org.jetbrains.kotlin.com.fasterxml.aalto.stax.OutputFactoryImpl { *; }
-keep class org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor { *; }
-keep class org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar { *; }
-keep class org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar { *; }
-keep class org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages$Extension { *; }
-keep class org.jetbrains.kotlin.extensions.StorageComponentContainerContributor { *; }
-keep class org.jetbrains.kotlin.extensions.TypeAttributeTranslatorExtension { *; }
-keep class org.jetbrains.kotlin.extensions.internal.CallResolutionInterceptorExtension { *; }
-keep class org.jetbrains.kotlin.extensions.internal.TypeResolutionInterceptorExtension { *; }
-keep class org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter { *; }
-keep class org.jetbrains.kotlin.idea.KotlinFileType { *; }
-keep class org.jetbrains.kotlin.idea.KotlinModuleFileType { *; }
-keep class org.jetbrains.kotlin.idea.references.KtDefaultAnnotationArgumentReference$Provider { *; }
-keep class org.jetbrains.kotlin.idea.references.ReadWriteAccessChecker { *; }
-keep class org.jetbrains.kotlin.light.classes.symbol.SymbolKotlinAsJavaSupport { *; }
-keep class org.jetbrains.kotlin.load.java.ErasedOverridabilityCondition { *; }
-keep class org.jetbrains.kotlin.load.java.FieldOverridabilityCondition { *; }
-keep class org.jetbrains.kotlin.load.java.JavaIncompatibilityRulesOverridabilityCondition { *; }
-keep class org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory { *; }
-keep class org.jetbrains.kotlin.plugin.references.SimpleNameReferenceExtension { *; }
-keep class org.jetbrains.kotlin.psi.KotlinReferenceProvidersService { *; }
-keep class org.jetbrains.kotlin.psi.stubs.elements.KtStubElementTypes { *; }
-keep class org.jetbrains.kotlin.references.KotlinPsiReferenceProviderContributor { *; }
-keep class org.jetbrains.kotlin.references.utils.KotlinKDocResolutionStrategyProviderService { *; }
-keep class org.jetbrains.kotlin.resolve.ExternalOverridabilityCondition { *; }
-keep class org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor { *; }
-keep class org.jetbrains.kotlin.resolve.extensions.AssignResolutionAltererExtension { *; }
-keep class org.jetbrains.kotlin.resolve.extensions.ExtraImportsProviderExtension { *; }
-keep class org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension { *; }
-keep class org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade { *; }
-keep class org.jetbrains.kotlin.resolve.jvm.KotlinToJvmSignatureMapper { *; }
-keep class org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension { *; }
-keep class org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension { *; }
-keep class org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver { *; }
-keep class org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCommandLineProcessor { *; }
-keep class org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar { *; }
-keep class org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingK2CompilerPluginRegistrar { *; }
-keep class org.jetbrains.kotlin.serialization.DescriptorSerializerPlugin { *; }
-keep class org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInsLoaderImpl { *; }
-keep class org.jetbrains.uast.UastLanguagePlugin { *; }
-keep class org.jetbrains.uast.analysis.UastAnalysisPlugin { *; }
-keep class org.jetbrains.uast.evaluation.UEvaluatorExtension { *; }
-keep class org.jetbrains.uast.generate.UastCodeGenerationPlugin { *; }
-keep class org.jetbrains.uast.java.JavaUastLanguagePlugin { *; }
-keep class org.jetbrains.uast.java.analysis.JavaUastAnalysisPlugin { *; }
-keep class org.jetbrains.uast.java.generate.JavaUastCodeGenerationPlugin { *; }
