package com.qingyu.imemodule.libs.expression.tokenizer

import com.qingyu.imemodule.libs.expression.function.Function

class FunctionToken(@JvmField val function: Function) : Token(TOKEN_FUNCTION)
