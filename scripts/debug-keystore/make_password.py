import secrets
import string

def generate_password():
    """Generates a secure, 16-character password."""

    # Define the character sets to use, excluding ambiguous characters
    lowercase = string.ascii_lowercase.replace('l', '')
    uppercase = string.ascii_uppercase.replace('I', '').replace('O', '')
    digits = string.digits.replace('1', '').replace('0', '')

    # Combine all valid characters
    valid_chars = lowercase + uppercase + digits

    # Use a list comprehension to generate the password
    password = ''.join(secrets.choice(valid_chars) for i in range(16))

    return password

if __name__ == '__main__':
    print(generate_password())
