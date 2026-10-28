package com.qingyu.imemodule.libs.expression.tokenizer

import com.qingyu.imemodule.libs.expression.operator.Operator

/**
 * Represents an operator used in expressions
 */
class OperatorToken(op: Operator?) : Token(TOKEN_OPERATOR) {

    @JvmField
    val operator: Operator

    init {
        requireNotNull(op) { "Operator is unknown for token." }
        operator = op
    }
}
