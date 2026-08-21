import openwakeword
from openwakeword.custom_model_generator import train_custom_model
import os

print("Generating custom OpenWakeWord model for 'Hey Bro'...")

# Note: this requires Piper TTS or some tts to generate the synthetic audio for training.
try:
    train_custom_model(
        target_phrase="hey bro",
        model_name="hey_bro_oww",
        output_dir="."
    )
    print("Generation complete!")
except Exception as e:
    print(f"Error generating model: {e}")
