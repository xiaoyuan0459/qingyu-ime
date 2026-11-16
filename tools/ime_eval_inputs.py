"""设备输入动作编码；九键数字逐键对应应用 T9PinYinUtils 的代表字母。"""

DIGIT_TO_KEY = dict(zip("23456789", "ADGJMPTW"))
LETTER_TO_DIGIT = {letter: digit for digit, letters in zip(
    "23456789", ("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")) for letter in letters}


def encode_digits(digits: str) -> str:
    if not digits or any(d not in DIGIT_TO_KEY for d in digits):
        raise ValueError("九键拼音动作仅允许 2..9")
    return "".join(DIGIT_TO_KEY[d] for d in digits)


def pinyin_digits(code: str) -> str:
    return "".join(LETTER_TO_DIGIT[letter] for letter in code.lower() if letter != " ")
