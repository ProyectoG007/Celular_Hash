import pyttsx3

VOZ_ESPANOL = r"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech\Voices\Tokens\TTS_MS_ES-ES_HELENA_11.0"


def hablar(texto):
    """Dice el texto en voz alta usando la voz en español instalada en Windows."""
    engine = pyttsx3.init()
    engine.setProperty("voice", VOZ_ESPANOL)
    engine.say(texto)
    engine.runAndWait()
