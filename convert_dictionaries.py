#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Script to convert dictionary .txt files to JSON format matching it_base.json structure
"""

import argparse
import json
import math
import os

def convert_txt_to_json(txt_file, json_file, scale_frequencies=False):
    """
    Convert a .txt dictionary file to JSON format.
    Expected format: word frequency (space-separated, one per line)
    Output format: [{"w": "word", "f": frequency}, ...]
    """
    entries = []
    
    print(f"Reading {txt_file}...")
    with open(txt_file, 'r', encoding='utf-8') as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            
            # Split by whitespace - word is everything except last token (frequency)
            parts = line.split()
            if len(parts) < 2:
                raise ValueError(f"Line {line_num}: expected word frequency")
            
            # Last part is frequency, everything else is the word
            frequency = parts[-1]
            word = ' '.join(parts[:-1])
            
            try:
                freq_int = int(frequency)
                if not 1 <= freq_int <= 2147483647:
                    raise ValueError("frequency must be between 1 and 2147483647")
                entries.append({"w": word, "f": freq_int})
            except ValueError as error:
                raise ValueError(f"Line {line_num}: invalid frequency {frequency}: {error}") from error
    
    if not entries:
        raise ValueError("Input contains no dictionary entries")

    if scale_frequencies:
        maximum = max(entry["f"] for entry in entries)
        for entry in entries:
            entry["f"] = 1 + round(254 * math.log1p(entry["f"]) / math.log1p(maximum))

    print(f"Writing {json_file} with {len(entries)} entries...")
    with open(json_file, 'w', encoding='utf-8') as f:
        f.write('[\n')
        for i, entry in enumerate(entries):
            # Format each entry on a single line, matching it_base.json format
            json_line = json.dumps(entry, ensure_ascii=False, separators=(',', ':'))
            if i < len(entries) - 1:
                f.write(f'  {json_line},\n')
            else:
                f.write(f'  {json_line}\n')
        f.write(']')
    
    print(f"Conversion complete: {len(entries)} entries written to {json_file}")

def main():
    parser = argparse.ArgumentParser(description="Convert UTF-8 word/frequency lines to dictionary JSON")
    parser.add_argument("input", nargs="?", help="Word/frequency text file")
    parser.add_argument("output", nargs="?", help="Output JSON file, e.g. ro_base.json")
    parser.add_argument("--scale-frequencies", action="store_true",
                        help="Log-scale corpus counts to the runtime ranking range 1–255")
    args = parser.parse_args()
    if args.input or args.output:
        if not args.input or not args.output:
            parser.error("input and output must be supplied together")
        try:
            convert_txt_to_json(args.input, args.output, args.scale_frequencies)
        except (OSError, ValueError) as error:
            parser.exit(1, f"Error: {error}\n")
        return

    if args.scale_frequencies:
        parser.error("--scale-frequencies requires input and output")

    base_dir = "app/src/main/assets/common/dictionaries"
    
    # Mapping of input files to output files
    conversions = [
        ("en_50k.txt", "en_base.json"),
        ("fr_50k.txt", "fr_base.json"),
        ("pl_50k.txt", "pl_base.json"),
        ("de_50k.txt", "de_base.json"),
        ("ru_50k.txt", "ru_base.json"),
        ("pt_50k.txt", "pt_base.json"),
        ("es_50k.txt", "es_base.json"),
    ]
    
    for txt_file, json_file in conversions:
        txt_path = os.path.join(base_dir, txt_file)
        json_path = os.path.join(base_dir, json_file)
        
        if not os.path.exists(txt_path):
            print(f"Error: {txt_path} not found!")
            continue
        
        convert_txt_to_json(txt_path, json_path)
        print()

if __name__ == "__main__":
    main()

