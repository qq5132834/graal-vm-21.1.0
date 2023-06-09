/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.parser;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.llvm.parser.model.ModelModule;
import com.oracle.truffle.llvm.parser.model.SymbolImpl;
import com.oracle.truffle.llvm.parser.model.functions.FunctionDeclaration;
import com.oracle.truffle.llvm.parser.model.functions.FunctionDefinition;
import com.oracle.truffle.llvm.parser.model.functions.FunctionSymbol;
import com.oracle.truffle.llvm.parser.model.symbols.constants.CastConstant;
import com.oracle.truffle.llvm.parser.model.symbols.globals.GlobalAlias;
import com.oracle.truffle.llvm.parser.model.symbols.globals.GlobalVariable;
import com.oracle.truffle.llvm.parser.model.target.TargetTriple;
import com.oracle.truffle.llvm.runtime.LLVMAlias;
import com.oracle.truffle.llvm.runtime.LLVMFunction;
import com.oracle.truffle.llvm.runtime.LLVMFunctionCode.Function;
import com.oracle.truffle.llvm.runtime.LLVMFunctionCode.LazyLLVMIRFunction;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.LLVMSymbol;
import com.oracle.truffle.llvm.runtime.datalayout.DataLayout;
import com.oracle.truffle.llvm.runtime.except.LLVMLinkerException;
import com.oracle.truffle.llvm.runtime.global.LLVMGlobal;
import com.oracle.truffle.llvm.runtime.options.SulongEngineOption;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LLVMParser {
    private final Source source;
    private final LLVMParserRuntime runtime;

    public LLVMParser(Source source, LLVMParserRuntime runtime) {
        System.out.println("LLVMParser文件路径:\n" + source.getPath());
        System.out.println("LLVMParser文件语言:\n" + source.getLanguage());
        //System.out.println("LLVMParser文件内容:\n" + source.getCharacters());
        this.source = source;
        this.runtime = runtime;
    }

    public LLVMParserResult parse(ModelModule module, DataLayout targetDataLayout) {
        System.out.println(LLVMParser.class.getSimpleName() + ".parser开始");
        List<GlobalVariable> externalGlobals = new ArrayList<>();
        List<GlobalVariable> definedGlobals = new ArrayList<>();
        List<FunctionSymbol> externalFunctions = new ArrayList<>();
        List<FunctionSymbol> definedFunctions = new ArrayList<>();

        defineGlobals(module.getGlobalVariables(), definedGlobals, externalGlobals);
        defineFunctions(module, definedFunctions, externalFunctions, targetDataLayout);
        defineAliases(module.getAliases());

        LLVMParserResult llvmParserResult = new LLVMParserResult(runtime, definedFunctions, externalFunctions, definedGlobals, externalGlobals, targetDataLayout, module.getTargetInformation(TargetTriple.class));

        //TODO 查看一下结果
        List<FunctionSymbol> definedFunctionSymbolList = llvmParserResult.getDefinedFunctions();
        definedFunctionSymbolList.stream().forEach(functionSymbol -> {
            Map<String, String> map = new HashMap<>();
            map.put("name", functionSymbol.getName());
            map.put("index", String.valueOf(functionSymbol.getIndex()));
            System.out.println("definedFunctionSymbolList:"+map.toString());
        });
        List<FunctionSymbol> externalFunctionSymbolList = llvmParserResult.getExternalFunctions();
        externalFunctionSymbolList.stream().forEach(functionSymbol -> {
            Map<String, String> map = new HashMap<>();
            map.put("name", functionSymbol.getName());
            map.put("index", String.valueOf(functionSymbol.getIndex()));
            System.out.println("externalFunctionSymbolList:"+map.toString());
        });
        System.out.println(llvmParserResult.toString());
        System.out.println(LLVMParser.class.getSimpleName() + ".parser完成");
        return llvmParserResult;
    }

    private void defineGlobals(List<GlobalVariable> globals, List<GlobalVariable> definedGlobals, List<GlobalVariable> externalGlobals) {
        for (GlobalVariable global : globals) {
            if (global.isExternal()) {
                externalGlobals.add(global);
            } else {
                defineGlobal(global);
                definedGlobals.add(global);
            }
        }
    }

    private void defineFunctions(ModelModule model, List<FunctionSymbol> definedFunctions, List<FunctionSymbol> externalFunctions, DataLayout dataLayout) {
        for (FunctionDefinition function : model.getDefinedFunctions()) {
            if (function.isExternal()) {
                externalFunctions.add(function);
            } else {
                defineFunction(function, model, dataLayout);
                definedFunctions.add(function);
            }
        }

        for (FunctionDeclaration function : model.getDeclaredFunctions()) {
            assert function.isExternal();
            externalFunctions.add(function);
        }
    }

    private void defineAliases(List<GlobalAlias> aliases) {
        for (GlobalAlias alias : aliases) {
            defineAlias(alias);
        }
    }

    private void defineGlobal(GlobalVariable global) {
        assert !global.isExternal();
        // handle the file scope
        LLVMGlobal globalSymbol = LLVMGlobal.create(global.getName(), global.getType(), global.getSourceSymbol(), global.isReadOnly(), global.getIndex(), runtime.getBitcodeID(), global.isExported(),
                        global.isExternalWeak());
        runtime.getFileScope().register(globalSymbol);
    }

    private void defineFunction(FunctionSymbol functionSymbol, ModelModule model, DataLayout dataLayout) {
        assert !functionSymbol.isExternal();
        // handle the file scope
        FunctionDefinition functionDefinition = (FunctionDefinition) functionSymbol;
        LazyToTruffleConverterImpl lazyConverter = new LazyToTruffleConverterImpl(runtime, functionDefinition, source, model.getFunctionParser(functionDefinition),
                        model.getFunctionProcessor(), dataLayout);
        Function function = new LazyLLVMIRFunction(lazyConverter);
        LLVMFunction llvmFunction = LLVMFunction.create(functionSymbol.getName(), function, functionSymbol.getType(), runtime.getBitcodeID(), functionSymbol.getIndex(),
                        functionDefinition.isExported(), runtime.getFile().getPath(), functionDefinition.isExternalWeak());
        lazyConverter.setRootFunction(llvmFunction);
        runtime.getFileScope().register(llvmFunction);
        final boolean cxxInterop = LLVMLanguage.getContext().getEnv().getOptions().get(SulongEngineOption.CXX_INTEROP);
        if (cxxInterop) {
            model.getFunctionParser(functionDefinition).parseLinkageName(runtime);
        }
    }

    private void defineAlias(GlobalAlias alias) {
        LLVMSymbol alreadyRegisteredSymbol = runtime.getFileScope().get(alias.getName());
        if (alreadyRegisteredSymbol != null) {
            // this alias was already registered by a recursive call
            assert alreadyRegisteredSymbol instanceof LLVMAlias;
            return;
        }
        defineAlias(alias.getName(), alias.isExported(), alias.getValue());
    }

    private void defineAlias(String aliasName, boolean isAliasExported, SymbolImpl value) {
        if (value instanceof FunctionSymbol) {
            FunctionSymbol function = (FunctionSymbol) value;
            defineAlias(function.getName(), aliasName, isAliasExported);
        } else if (value instanceof GlobalVariable) {
            GlobalVariable global = (GlobalVariable) value;
            defineAlias(global.getName(), aliasName, isAliasExported);
        } else if (value instanceof GlobalAlias) {
            GlobalAlias target = (GlobalAlias) value;
            defineAlias(target);
            defineAlias(target.getName(), aliasName, isAliasExported);
        } else if (value instanceof CastConstant) {
            // TODO (chaeubl): this is not perfectly accurate as we are loosing the type cast
            CastConstant cast = (CastConstant) value;
            defineAlias(aliasName, isAliasExported, cast.getValue());
        } else {
            throw new LLVMLinkerException("Unknown alias type: " + value.getClass());
        }
    }

    private void defineAlias(String existingName, String newName, boolean newExported) {
        // handle the file scope
        LLVMSymbol aliasTarget = runtime.lookupSymbol(existingName);
        LLVMAlias aliasSymbol = new LLVMAlias(newName, aliasTarget, newExported);
        runtime.getFileScope().register(aliasSymbol);
    }
}
